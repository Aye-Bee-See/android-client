package me.paxana.abcmailbox.data.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.crypto.Reader
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.api.AddEnvelopeRequest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** The group's opened keypair. `publicKey` is base64, as it travels; `version` goes on everything sealed to it. */
class GroupKey(val groupId: Int, val keyPair: Sodium.KeyPair, val publicKey: String, val version: Int, /** This account is the chapter's group-owner admin. */ val isOwner: Boolean = false)

/** Where a group member stands with their group's key. Screens explain each case; none is an error to hide. */
sealed interface GroupKeyState {
  /** Server mode, a writer's account, or nothing loaded yet. */
  data object NotNeeded : GroupKeyState
  /** The member's own key is locked on this device, so nothing sealed to them can be opened. */
  data object Locked : GroupKeyState
  /**
   * The group is waiting for the network's approval, or is suspended. Its accounts read what the public reads and get
   * 403 on every group key endpoint, so there is nothing to set up yet, and nothing should be offered.
   */
  data class GroupNotActive(val groupId: Int) : GroupKeyState
  /** Nobody has made the group's keypair yet. Any member can, once. */
  data class NotSetUp(val groupId: Int) : GroupKeyState
  /** The group has a key, but no holder has handed it to this member. */
  data class NotHeld(val groupId: Int) : GroupKeyState
  data class Ready(val key: GroupKey) : GroupKeyState
  data class Failed(val error: AppError) : GroupKeyState
}

/**
 * What a group member needs in order to read: the group's private key (sealed
 * to the member in their key bundle), and through it the private keys of the
 * writers the group still holds in custody. Loaded once per sign-in, kept in
 * memory only, never written to disk: the member's own key in the [KeyVault]
 * is enough to open all of it again on the next launch.
 */
interface GroupKeyring {
  val state: StateFlow<GroupKeyState>
  /** Loads if needed. Cheap when already loaded, and a no-op for writers and in server mode. */
  suspend fun load(force: Boolean = false): GroupKeyState
  fun groupKey(): GroupKey?
  /** The keypair of a writer in this group's custody, if loaded. */
  fun writerKey(writerId: Int): Sodium.KeyPair?
  /** A writer this device just created: usable without a reload. */
  fun remember(writerId: Int, keyPair: Sodium.KeyPair)
  fun forget()
}

@Singleton
class DefaultGroupKeyring @Inject constructor(
  private val modes: EncryptionModeRepository,
  private val sessions: SessionRepository,
  private val vault: KeyVault,
  private val engine: CryptoEngine,
  private val authApi: AuthApi,
  private val groupApi: GroupApi,
  private val json: Json,
  @ApplicationScope scope: CoroutineScope,
) : GroupKeyring {

  private val _state = MutableStateFlow<GroupKeyState>(GroupKeyState.NotNeeded)
  override val state: StateFlow<GroupKeyState> = _state.asStateFlow()

  private val lock = Mutex()
  @Volatile private var loadedFor: Int? = null
  private val writers = java.util.concurrent.ConcurrentHashMap<Int, Sodium.KeyPair>()

  // Below the properties on purpose: Kotlin runs initialisers top to bottom, and forget() touches them.
  init {
    // Signing out (or a refused token) wipes the keys now, not at the next load. The session
    // repository cannot call this class (this class depends on it), so this class watches instead.
    scope.launch { sessions.state.collect { if (it is SessionState.SignedOut) forget() } }
  }

  private val member get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.takeIf { it.isStaff && it.chapterId != null }

  override fun groupKey(): GroupKey? = (state.value as? GroupKeyState.Ready)?.key?.takeIf { loadedFor == member?.id }
  override fun writerKey(writerId: Int): Sodium.KeyPair? = writers[writerId]?.takeIf { loadedFor == member?.id }
  override fun remember(writerId: Int, keyPair: Sodium.KeyPair) { if (groupKey() != null) writers[writerId] = keyPair }

  /** Signing out: what is held is zeroed as well as dropped. */
  override fun forget() = reset(zero = true)

  /**
   * [zero]: overwrite the private keys, not only drop them. Only signing out does that. A reload (a key event arrived,
   * a hand-over was refused as stale) must not: a caller may be holding the old `GroupKey` across a network call, about
   * to seal it (`handKeyTo`), and zeroing it under them would hand somebody a sealed copy of nothing. Replaced keys
   * stay whole until they are garbage, which is the same in-memory lifetime they had before they were replaced.
   */
  private fun reset(zero: Boolean) {
    if (zero) writers.values.forEach { it.privateKey.fill(0) }
    writers.clear()
    if (zero) (_state.value as? GroupKeyState.Ready)?.key?.keyPair?.privateKey?.fill(0)
    loadedFor = null
    _state.value = GroupKeyState.NotNeeded
  }

  override suspend fun load(force: Boolean): GroupKeyState = lock.withLock {
    val me = member
    if (me == null || modes.current() != EncryptionMode.E2E) {
      if (loadedFor != null) reset(zero = false)
      return@withLock GroupKeyState.NotNeeded
    }
    // Only a loaded key is final; every other state is worth asking about again (someone may have handed the key over).
    if (!force && loadedFor == me.id && _state.value is GroupKeyState.Ready) return@withLock _state.value
    reset(zero = false)
    val mine = vault.keyPair(me.id) ?: return@withLock set(GroupKeyState.Locked)

    val org = when (val r = apiCall(json) { authApi.keys() }) {
      is ApiResult.Failure -> return@withLock set(GroupKeyState.Failed(r.error))
      is ApiResult.Success -> r.value.data?.orgKey
    }
    val groupId = org?.chapterId ?: checkNotNull(me.chapterId)
    val publicKey = org?.chapterPublicKey ?: run {
      // "No key yet" is also all a pending or suspended group's member is told. Asking any group key endpoint tells
      // them apart: for such a group it answers 403. Without this the page offers a set-up that can only be refused.
      val mayAct = apiCall(json) { groupApi.members(groupId) }
      return@withLock set(if ((mayAct as? ApiResult.Failure)?.error is AppError.Forbidden) GroupKeyState.GroupNotActive(groupId) else GroupKeyState.NotSetUp(groupId))
    }
    val sealed = org.wrappedOrgPrivateKey ?: return@withLock set(GroupKeyState.NotHeld(groupId))
    val groupPair = runCatching { engine.openSealedKey(sealed, mine, publicKey) }.getOrElse {
      // Sealed to a key this member no longer has (they recovered onto a new keypair), or tampered with.
      return@withLock set(GroupKeyState.NotHeld(groupId))
    }
    loadedFor = me.id
    set(GroupKeyState.Ready(GroupKey(groupId, groupPair, publicKey, org.keyVersion ?: 1, org.isOwner)))

    // Custody keys. A failure here leaves those writers' own envelopes closed; the group's envelopes still open.
    (apiCall(json) { groupApi.writers() } as? ApiResult.Success)?.value?.data.orEmpty().forEach { w ->
      val sealedWriterKey = w.orgWrappedPrivateKey ?: return@forEach
      runCatching { engine.openSealedKey(sealedWriterKey, groupPair, w.publicKey) }.onSuccess { writers[w.id] = it }
    }
    catchUpEnvelopes(groupPair)
    _state.value
  }

  /**
   * Quietly, after sign-in: a reply recorded for a writer who had no key yet was sealed to the group alone.
   * If they have a key by now, give them their envelope. Best effort; whatever fails is offered again next time.
   */
  private suspend fun catchUpEnvelopes(groupPair: Sodium.KeyPair) {
    val waiting = (apiCall(json) { groupApi.missingEnvelopes() } as? ApiResult.Success)?.value?.data.orEmpty()
    for (w in waiting) {
      val theirKey = w.publicKey ?: continue
      val sealedToGroup = w.wrappedKey ?: continue
      val envelope = runCatching { engine.sealContentKey(engine.openEnvelope(sealedToGroup, groupPair), Reader(w.readerType, w.readerId, theirKey)) }.getOrNull() ?: continue
      apiCall(json) { groupApi.addEnvelope(AddEnvelopeRequest(w.message, envelope.readerType, envelope.readerId, envelope.wrappedKey, envelope.keyVersion)) }
    }
  }

  private fun set(state: GroupKeyState): GroupKeyState { _state.value = state; return state }
}

package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.api.AddEnvelopeRequest
import me.paxana.abcmailbox.data.api.AddWriterRequest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.LettersSentBeforeRequest
import me.paxana.abcmailbox.data.api.MessageDto
import me.paxana.abcmailbox.data.api.BatchStatusRequest
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.GroupKeyRequest
import me.paxana.abcmailbox.data.api.IssueTokenRequest
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.api.MemberKeyRequest
import me.paxana.abcmailbox.data.api.MemberRef
import me.paxana.abcmailbox.data.api.Page
import me.paxana.abcmailbox.data.api.UpdateUserRequest
import me.paxana.abcmailbox.data.api.StatusRequest
import me.paxana.abcmailbox.data.api.WriterDto
import me.paxana.abcmailbox.data.api.WriterRef
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.api.toPage
import me.paxana.abcmailbox.data.crypto.CryptoEngine
import me.paxana.abcmailbox.data.crypto.GroupKey
import me.paxana.abcmailbox.data.crypto.GroupKeyState
import me.paxana.abcmailbox.data.crypto.GroupKeyring
import me.paxana.abcmailbox.data.crypto.KeyVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.GroupMember
import me.paxana.abcmailbox.domain.IssuedToken
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.QueueItem
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** How a letter came back: the reason the group chose, and what the envelope said (200 characters, never encrypted, shown to the writer). */
const val BATCH_MAX = 200

/**
 * A group's numbers as its members see them (API PR #112). Only [before] is typed by anyone; the server counts the
 * rest. [published] is what the public page says: null until the total reaches twenty, so a small group is not put on show.
 */
data class GroupNumbers(val groupName: String, val before: Int, val countedHere: Int, val published: String?, val averageDaysToMail: Int?) {
  val total: Int get() = before + countedHere
}

data class ReturnedAs(val reason: me.paxana.abcmailbox.domain.ReturnReason, val note: String? = null) { companion object { const val NOTE_MAX = 200 } }

/**
 * An API from before PR #106 ignores `held=true` and lists every queued letter, which would show a group its whole
 * queue under "Held". So the page is checked on the phone (the iOS app found this on its simulator). If the server
 * did not filter, its total and its further pages mean nothing either: what is held on this page is all there is.
 */
internal fun Page<QueueItem>.onlyHeld(): Page<QueueItem> {
  val held = items.filter { it.letter.isHeld }
  return if (held.size == items.size) this else Page(held, held.size, page, pageSize)
}

interface GroupRepository {
  /** Letters the group relays, in one status. Each comes with the prisoner, for addressing. */
  fun queue(groupId: Int, status: LetterStatus): Flow<PagingData<QueueItem>>
  /** The queued letters that are held: the person was moved or freed after they were written. */
  fun held(groupId: Int): Flow<PagingData<QueueItem>> = kotlinx.coroutines.flow.emptyFlow()
  suspend fun queueItem(messageId: Int): ApiResult<QueueItem>
  /** Null when the server does not count yet (an API from before PR #112). */
  suspend fun numbers(): ApiResult<GroupNumbers?> = ApiResult.Success(null)
  suspend fun setLettersSentBefore(count: Int): ApiResult<Unit> = ApiResult.Failure(AppError.Unexpected(UnsupportedOperationException()))
  /** Moves several letters together, all or none (API PR #111). Answers how many moved; a failure names the letter that stopped it. */
  suspend fun setStatusOfMany(messageIds: List<Int>, status: LetterStatus): ApiResult<Int> = ApiResult.Failure(AppError.Unexpected(UnsupportedOperationException()))
  /** [returned] is required for, and only for, `RETURNED`. [release] prints a held letter knowingly. */
  suspend fun setStatus(messageId: Int, status: LetterStatus, returned: ReturnedAs? = null, release: Boolean = false): ApiResult<Letter>
  suspend fun writers(): ApiResult<List<ManagedWriter>>
  suspend fun addWriter(name: String, email: String?, note: String?): ApiResult<ManagedWriter>
  suspend fun issueToken(writerId: Int): ApiResult<IssuedToken>
  suspend fun revokeToken(writerId: Int): ApiResult<Unit>

  // End-to-end mode only ------------------------------------------------------------------
  /** Where this member stands with the group key. Always `NotNeeded` in server mode. */
  val keyState: StateFlow<GroupKeyState>
  suspend fun refreshKeyState(): GroupKeyState
  /** Makes the group's keypair on this device, once, and seals the private half to this member. */
  suspend fun setUpGroupKey(): ApiResult<Unit>
  suspend fun members(): ApiResult<List<GroupMember>>
  suspend fun handKeyTo(memberId: Int): ApiResult<Unit>
  suspend fun stopHandingKeyTo(memberId: Int): ApiResult<Unit>
  /** The facility's other relay groups: the only groups the server lets a letter be shared with. Empty in server mode. */
  suspend fun partnersFor(prisonerId: Int): List<Group>
  /** Gives a partner relay group the means to read one letter. */
  suspend fun shareWith(messageId: Int, partnerGroupId: Int): ApiResult<Unit>
}

@Singleton
class DefaultGroupRepository @Inject constructor(
  private val api: GroupApi,
  private val letters: LettersRepository,
  private val directory: DirectoryRepository,
  private val codec: LetterCodec,
  private val json: Json,
  private val keyring: GroupKeyring,
  private val engine: CryptoEngine,
  private val vault: KeyVault,
  private val sessions: SessionRepository,
  private val authApi: AuthApi,
  private val lettersApi: LettersApi,
  private val strings: Strings,
) : GroupRepository {

  // Not called `me`: that would shadow the `me.paxana…` package root inside this class.
  private val viewer get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user

  /** The opened group key, or the reason it is not available as an error a screen can show. */
  private suspend fun groupKey(force: Boolean = false): ApiResult<GroupKey> = when (val s = keyring.load(force)) {
    is GroupKeyState.Ready -> ApiResult.Success(s.key)
    is GroupKeyState.Failed -> ApiResult.Failure(s.error)
    GroupKeyState.Locked -> ApiResult.Failure(codec.locked)
    is GroupKeyState.NotSetUp -> ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_group_key_not_set_up)))
    is GroupKeyState.NotHeld -> ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_group_key_not_held)))
    is GroupKeyState.GroupNotActive -> ApiResult.Failure(AppError.Forbidden(strings.get(R.string.group_not_active_text)))
    GroupKeyState.NotNeeded -> ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))
  }

  // Since API PR #111 a queue row brings its prisoner and facility with it. Against an older API a row names the
  // prisoner by id only, and then each distinct prisoner is fetched once and remembered, as before.
  private val prisoners = ConcurrentHashMap<Int, Prisoner>()
  private suspend fun prisoner(id: Int): Prisoner? =
    prisoners[id] ?: (directory.prisoner(id) as? ApiResult.Success)?.value?.also { prisoners[id] = it }
  private suspend fun MessageDto.toQueueItem() = QueueItem(codec.incoming(this), prisonerDetails?.toDomain() ?: prisoner(prisoner))

  override fun queue(groupId: Int, status: LetterStatus): Flow<PagingData<QueueItem>> = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
    PagePagingSource { page, size ->
      codec.ready()
      when (val r = apiCall(json) { api.relayed(groupId, status.key, page = page, pageSize = size) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> {
          val p = r.value.toPage()
          ApiResult.Success(Page(p.items.map { it.toQueueItem() }, p.total, p.page, p.pageSize))
        }
      }
    }
  }.flow

  override fun held(groupId: Int): Flow<PagingData<QueueItem>> = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
    PagePagingSource { page, size ->
      codec.ready()
      when (val r = apiCall(json) { api.relayed(groupId, LetterStatus.QUEUED.key, held = true, page = page, pageSize = size) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> {
          val p = r.value.toPage()
          ApiResult.Success(Page(p.items.map { it.toQueueItem() }, p.total, p.page, p.pageSize).onlyHeld())
        }
      }
    }
  }.flow

  override suspend fun queueItem(messageId: Int): ApiResult<QueueItem> {
    codec.ready()
    return apiCall(json) { api.letter(messageId) }.map { checkNotNull(it.data).toQueueItem() }
  }

  override suspend fun numbers(): ApiResult<GroupNumbers?> {
    val groupId = viewer?.chapterId ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))
    return apiCall(json) { api.ownGroup(groupId) }.map { env ->
      val dto = checkNotNull(env.data)
      val group = dto.toDomain()
      // Staff-only fields: absent means the server is older than the counting, not that the count is zero.
      if (dto.lettersSentBefore == null && dto.lettersCounted == null) null
      else GroupNumbers(group.name, dto.lettersSentBefore ?: 0, dto.lettersCounted ?: 0, group.lettersSent, group.averageDaysToMail)
    }
  }

  override suspend fun setLettersSentBefore(count: Int): ApiResult<Unit> {
    val groupId = viewer?.chapterId ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))
    if (count < 0) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_not_negative))))
    // Only the id and the one field: `lettersSent` and `averageTimeDays` are the server's, and sending them changes nothing.
    return apiCall(json) { api.setLettersSentBefore(LettersSentBeforeRequest(groupId, count)) }.map { }
  }

  override suspend fun setStatusOfMany(messageIds: List<Int>, status: LetterStatus): ApiResult<Int> {
    if (messageIds.isEmpty()) return ApiResult.Success(0)
    // The API takes 200 at a time. More than that would stop being all-or-none, so it is refused here, not split quietly.
    if (messageIds.size > BATCH_MAX) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_batch_too_many, BATCH_MAX))))
    return when (val r = apiCall(json) { api.setStatusBatch(BatchStatusRequest(messageIds.distinct(), status.key)) }) {
      is ApiResult.Success -> ApiResult.Success(r.value.data?.count ?: messageIds.size)
      // An API from before PR #111 has no such address. Say that, not "not found", which would read as a missing letter.
      // Express answers an address it does not have with "Cannot PUT /…"; a letter that does not exist reads "Message 42 not found".
      is ApiResult.Failure -> if (r.error is AppError.NotFound && r.error.info?.startsWith("Cannot ") == true) ApiResult.Failure(AppError.Server(404, strings.get(R.string.error_batch_not_supported))) else r
    }
  }

  override suspend fun setStatus(messageId: Int, status: LetterStatus, returned: ReturnedAs?, release: Boolean): ApiResult<Letter> {
    codec.ready()
    val request = StatusRequest(messageId, status.key, reason = returned?.reason?.key, note = returned?.note?.trim()?.take(ReturnedAs.NOTE_MAX)?.ifBlank { null }, release = true.takeIf { release })
    return apiCall(json) { api.setStatus(request) }.map { codec.incoming(checkNotNull(it.data)) }
  }

  override suspend fun writers(): ApiResult<List<ManagedWriter>> =
    apiCall(json) { api.writers() }.map { env -> env.data.orEmpty().filter { it.anonymousForChapter == null }.map { it.toDomain() }.sortedBy { it.name.lowercase() } }

  override suspend fun addWriter(name: String, email: String?, note: String?): ApiResult<ManagedWriter> {
    val plain = AddWriterRequest(name.trim(), email?.trim()?.ifBlank { null }, note?.trim()?.ifBlank { null })
    if (!codec.isEndToEnd()) return apiCall(json) { api.addWriter(plain) }.map { checkNotNull(it.data).toDomain() }
    // End-to-end: the writer's keypair is made here and the private half sealed to the group (custody),
    // so the group can write and read for them until they claim the account. A 409 means the group key
    // was rotated since this device opened it: open the new one and seal again, once.
    repeat(2) { attempt ->
      val group = when (val g = groupKey(force = attempt == 1)) { is ApiResult.Failure -> return g; is ApiResult.Success -> g.value }
      val made = engine.newKeyPairSealedTo(group.publicKey)
      when (val r = apiCall(json) { api.addWriter(plain.copy(publicKey = made.publicKey, orgWrappedPrivateKey = made.sealedPrivateKey, orgKeyVersion = group.version)) }) {
        is ApiResult.Success -> return ApiResult.Success(checkNotNull(r.value.data).toDomain().also { keyring.remember(it.id, made.keyPair) })
        is ApiResult.Failure -> if (r.error !is AppError.Conflict || attempt == 1) return r
      }
    }
    error("unreachable")
  }

  override suspend fun issueToken(writerId: Int): ApiResult<IssuedToken> {
    if (!codec.isEndToEnd()) {
      return when (val r = apiCall(json) { api.issueToken(IssueTokenRequest(writerId)) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> r.value.data?.token?.let { ApiResult.Success(IssuedToken(it, r.value.data?.expiresAt.toInstantOrNull())) }
          ?: ApiResult.Failure(AppError.Unexpected(IllegalStateException("The server did not return a token.")))
      }
    }
    // End-to-end: the token is a secret the server must never see. It is made here, the writer's private
    // key is wrapped under it, and the server gets the wrapped key and a hash to recognise the token by.
    val writerKey = when (val k = custodyKey(writerId)) { is ApiResult.Failure -> return k; is ApiResult.Success -> k.value }
    val made = engine.newClaimToken(writerKey.privateKey)
    val request = IssueTokenRequest(writerId, made.tokenHash, made.wrapped.wrapped, made.wrapped.salt, made.wrapped.params)
    return apiCall(json) { api.issueToken(request) }.map { IssuedToken(made.token, it.data?.expiresAt.toInstantOrNull()) }
  }

  /**
   * The keypair of a writer in custody. A writer made before the server was end-to-end has none;
   * the API lets the managing group give them one, once, and this does.
   */
  private suspend fun custodyKey(writerId: Int): ApiResult<Sodium.KeyPair> {
    val group = when (val g = groupKey()) { is ApiResult.Failure -> return g; is ApiResult.Success -> g.value }
    keyring.writerKey(writerId)?.let { return ApiResult.Success(it) }
    val writer = when (val r = apiCall(json) { api.writers() }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> r.value.data.orEmpty().firstOrNull { it.id == writerId } ?: return ApiResult.Failure(AppError.NotFound(strings.get(R.string.error_writer_not_managed)))
    }
    if (writer.publicKey != null) {
      val sealed = writer.orgWrappedPrivateKey ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_writer_key_not_held)))
      return runCatching { engine.openSealedKey(sealed, group.keyPair, writer.publicKey) }.fold(
        { keyring.remember(writerId, it); ApiResult.Success(it) },
        { ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_writer_key_old_group_key))) },
      )
    }
    val made = engine.newKeyPairSealedTo(group.publicKey)
    return when (val r = apiCall(json) { authApi.updateUser(UpdateUserRequest(id = writerId, publicKey = made.publicKey, orgWrappedPrivateKey = made.sealedPrivateKey, orgKeyVersion = group.version)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> { keyring.remember(writerId, made.keyPair); ApiResult.Success(made.keyPair) }
    }
  }

  override suspend fun revokeToken(writerId: Int): ApiResult<Unit> = apiCall(json) { api.revokeToken(WriterRef(writerId)) }.map { }

  override val keyState: StateFlow<GroupKeyState> get() = keyring.state
  override suspend fun refreshKeyState(): GroupKeyState = keyring.load(force = true)

  override suspend fun setUpGroupKey(): ApiResult<Unit> {
    val user = viewer ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val groupId = user.chapterId ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))
    val mine = vault.keyPair(user.id) ?: return ApiResult.Failure(codec.locked)
    val made = engine.newKeyPairSealedTo(Base64.getEncoder().encodeToString(mine.publicKey))
    val result = apiCall(json) { api.bootstrapGroupKey(GroupKeyRequest(groupId, made.publicKey, made.sealedPrivateKey)) }
    made.keyPair.privateKey.fill(0)
    // Success or not, ask again: on a 409 another member set the key up first, and the state should say so.
    keyring.load(force = true)
    return result.map { }
  }

  override suspend fun members(): ApiResult<List<GroupMember>> {
    val user = viewer ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val groupId = user.chapterId ?: return ApiResult.Success(emptyList())
    return apiCall(json) { api.members(groupId) }.map { env ->
      env.data?.members.orEmpty().map { GroupMember(it.id, it.name?.takeIf { n -> n.isNotBlank() } ?: it.username ?: strings.get(R.string.member_numbered, it.id), it.publicKey != null, it.holdsGroupKey, it.id == user.id) }
    }
  }

  override suspend fun handKeyTo(memberId: Int): ApiResult<Unit> {
    val group = when (val g = groupKey()) { is ApiResult.Failure -> return g; is ApiResult.Success -> g.value }
    // Their public key comes from the members list, which only this group and admins can read.
    val theirKey = when (val r = apiCall(json) { api.members(group.groupId) }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> r.value.data?.members.orEmpty().firstOrNull { it.id == memberId }?.publicKey
        ?: return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_member_no_key))))
    }
    val result = apiCall(json) { api.handKey(MemberKeyRequest(group.groupId, memberId, engine.sealPrivateKey(group.keyPair.privateKey, theirKey), keyVersion = group.version)) }
    // The group rotated its key while this phone was sealing the old one. Forget the old one now; the next try uses the new.
    if (((result as? ApiResult.Failure)?.error as? AppError.Conflict)?.name == "KeyVersionError") {
      keyring.load(force = true)
      return ApiResult.Failure(AppError.Conflict(strings.get(R.string.error_group_key_rotated), "KeyVersionError"))
    }
    return result.map { }
  }

  override suspend fun stopHandingKeyTo(memberId: Int): ApiResult<Unit> {
    val groupId = viewer?.chapterId ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))
    return apiCall(json) { api.takeKey(MemberRef(groupId, memberId)) }.map { }
  }

  override suspend fun partnersFor(prisonerId: Int): List<Group> {
    val mine = keyring.groupKey()?.groupId ?: return emptyList()
    val facilityId = prisoner(prisonerId)?.facilityId ?: return emptyList()
    val facility = (directory.facility(facilityId) as? ApiResult.Success)?.value ?: return emptyList()
    return facility.relayGroups.filter { it.id != mine && (it.accountStatus == null || it.accountStatus == "active") }
  }

  override suspend fun shareWith(messageId: Int, partnerGroupId: Int): ApiResult<Unit> {
    val dto = when (val r = apiCall(json) { lettersApi.message(messageId) }) { is ApiResult.Failure -> return r; is ApiResult.Success -> checkNotNull(r.value.data) }
    val envelope = when (val e = codec.envelopeFor(dto, partnerGroupId)) { is ApiResult.Failure -> return e; is ApiResult.Success -> e.value }
    return apiCall(json) { api.addEnvelope(AddEnvelopeRequest(messageId, envelope.readerType, envelope.readerId, envelope.wrappedKey, envelope.keyVersion)) }.map { }
  }
}

fun WriterDto.toDomain() = ManagedWriter(
  id = id,
  name = name?.takeIf { it.isNotBlank() } ?: username ?: "Writer $id",
  email = email?.takeIf { it.isNotBlank() && !it.endsWith("@managed.example") },
  note = managerNote?.takeIf { it.isNotBlank() },
  tokenExpiresAt = claimToken?.expiresAt.toInstantOrNull(),
)

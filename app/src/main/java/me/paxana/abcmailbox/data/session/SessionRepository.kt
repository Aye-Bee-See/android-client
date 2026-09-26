package me.paxana.abcmailbox.data.session

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.paxana.abcmailbox.crypto.KdfParams
import me.paxana.abcmailbox.data.crypto.SplitKeys
import me.paxana.abcmailbox.data.api.DeletionReportDto
import me.paxana.abcmailbox.data.api.DeleteAccountRequest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import me.paxana.abcmailbox.data.crypto.lockedError
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.crypto.AccountKeyFields
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.api.ClaimInfoDto
import me.paxana.abcmailbox.data.api.ClaimRequest
import me.paxana.abcmailbox.data.api.KeyBundleDto
import me.paxana.abcmailbox.data.api.KeyFieldsRequest
import me.paxana.abcmailbox.data.api.RecoverFinishRequest
import me.paxana.abcmailbox.data.crypto.CryptoEngine
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.crypto.KeyVault
import me.paxana.abcmailbox.data.crypto.NewAccountKeys
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.api.UpdateUserRequest
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.LoginParamsDto
import me.paxana.abcmailbox.data.api.LoginData
import me.paxana.abcmailbox.data.api.LoginRequest
import me.paxana.abcmailbox.data.api.LogoutRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.di.ApplicationScope
import me.paxana.abcmailbox.domain.ClaimInfo
import me.paxana.abcmailbox.domain.Invitation
import me.paxana.abcmailbox.domain.PenName
import me.paxana.abcmailbox.data.api.JoinRequest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that signs in and out. Screens observe [state]; nothing else
 * touches the token. ViewModels depend on this interface so tests can hand
 * them a scripted fake; Hilt binds [DefaultSessionRepository] in the app.
 */
interface SessionRepository {
  val state: StateFlow<SessionState>

  /** Emits when the server ended the session (revoked, expired, banned), so the UI can say so once. */
  val expired: SharedFlow<Unit>

  /**
   * [olderAccount]: the person chose "sign in with my password itself", for an account made before the split scheme on a
   * server that now calls every name split. The password is sent as it is, once, by that choice and never by the app's own.
   */
  suspend fun login(username: String, password: String, olderAccount: Boolean = false): ApiResult<Session>
  suspend fun logout(everywhere: Boolean = false): ApiResult<Unit>

  /** Who a claim token is for; the token must already be normalised. */
  suspend fun claimInfo(token: String): ApiResult<ClaimInfo>

  /** Claims the account, then signs in with the new credentials. */
  suspend fun claim(token: String, username: String, password: String, email: String?, penName: String? = null): ApiResult<Session>

  /** Who is vouching for an invite code (API PR #116), before a username is asked for. The code must already be normalised. */
  suspend fun joinInfo(code: String): ApiResult<Invitation>

  /** Makes an account with an invite code, with keys made here as on a claim, then signs in with it. */
  suspend fun join(code: String, username: String, password: String, email: String?, name: String?, penName: String? = null): ApiResult<Session>

  /** Verifies `current` by signing in with it, changes the password, and adopts the fresh token. */
  suspend fun changePassword(current: String, new: String): ApiResult<Unit>

  /**
   * Deletes the signed-in account on the server. Only if that succeeded: runs [wipe] with the account's id
   * (whatever else on this phone belonged to it), then forgets the session and the keys. In that order,
   * because forgetting the session is what makes the screens change, and because the wipe must not be cut
   * short by the screen that asked for it going away. A failure changes nothing, here or there.
   */
  suspend fun deleteAccount(password: String, wipe: suspend (userId: Int) -> Unit = {}): ApiResult<DeletionReportDto>

  // End-to-end mode -----------------------------------------------------------------------

  /** A recovery code that was just created and must be shown to the writer exactly once. */
  val pendingRecoveryCode: StateFlow<String?>

  /**
   * The writer confirmed they saved the code. Keys made at sign-in go to the server only now (`PUT /auth/keys`, in the
   * order docs/E2E-MIGRATION.md gives), so no account is ever guarded by a code nobody saw: until this succeeds nothing
   * is uploaded, and a sign-in interrupted on the code screen makes fresh keys and a fresh code the next time. Answers
   * how many earlier letters the server sealed to the new key (`caughtUp.sealed`), 0 when none or when the keys went up
   * with the account (a claim, a join). A failure keeps the code and the keys for another try, except a
   * [AppError.Conflict]: another device set this account's key first, the code shown here opens nothing, and it is
   * forgotten; the account unlocks with its password as usual.
   */
  suspend fun recoveryCodeSaved(): ApiResult<Int>

  /** True when signed in to an end-to-end server without the private key on this device. */
  val keysLocked: StateFlow<Boolean>

  /**
   * Fetches the key bundle and unwraps it with the password. An account with no key yet (a sign-in that ended on the
   * recovery code screen without its confirmation) is signed in again, which checks the password with the server and
   * makes the keys and a new recovery code, exactly as a first sign-in does.
   */
  suspend fun unlock(password: String): ApiResult<Unit>

  /**
   * Whether the signed-in account's password never leaves the phone, for the unlock prompt to say so only when it is
   * true. A split account sends a key derived from it, even when unlocking signs in again; this phone remembers every
   * name it has signed in to that way. Anything else (an account from before, or a session saved before this phone
   * kept that memory) may send the password itself when unlocking has to sign in again.
   */
  suspend fun passwordStaysOnPhone(): Boolean

  /** Recovery with the saved code: proves possession of the key, sets a new password, signs in. */
  suspend fun recover(username: String, recoveryCode: String, newPassword: String): ApiResult<Session>
}

/**
 * Lives as long as the process (a Hilt singleton) and does its bookkeeping on
 * an application-wide coroutine scope, because sign-in state must outlive any
 * one screen.
 */
private const val SPLIT = "split"

@Singleton
class DefaultSessionRepository @Inject constructor(
  private val store: SessionStore,
  private val api: AuthApi,
  private val cache: SessionCache,
  private val json: Json,
  private val modes: EncryptionModeRepository,
  private val engine: CryptoEngine,
  private val vault: KeyVault,
  @ApplicationScope private val scope: CoroutineScope,
  private val strings: Strings,
  private val schemes: SchemeMemory,
) : SessionRepository {

  private val _pendingRecoveryCode = MutableStateFlow<String?>(null)
  override val pendingRecoveryCode: StateFlow<String?> = _pendingRecoveryCode.asStateFlow()

  /** Keys made at sign-in, in memory only until the writer confirms they saved the code ([recoveryCodeSaved]). */
  private class PendingKeys(val userId: Int, val keys: NewAccountKeys)
  @Volatile private var pendingKeys: PendingKeys? = null
  private val uploading = Mutex()

  private fun forgetPendingKeys() { pendingKeys = null; _pendingRecoveryCode.value = null }

  // On the application scope: leaving the screen (a rotation, the app going to the background) must not cancel an
  // upload the server may already have taken. Under a lock, so a double tap sends once.
  override suspend fun recoveryCodeSaved(): ApiResult<Int> = scope.async { uploading.withLock { uploadPendingKeys() } }.await()

  private suspend fun uploadPendingKeys(): ApiResult<Int> {
    // A claim or a join: the keys went up with the account, so there is only the code to forget.
    val pending = pendingKeys ?: run { _pendingRecoveryCode.value = null; return ApiResult.Success(0) }
    // Sending the same public key again is an ordinary update to the API, so a try whose answer was lost is safe to repeat.
    return when (val sent = apiCall(json) { api.putKeys(pending.keys.fields.toRequest()) }) {
      is ApiResult.Success -> {
        vault.store(pending.userId, pending.keys.keyPair)
        forgetPendingKeys()
        ApiResult.Success(sent.value.data.caughtUpSealed())
      }
      is ApiResult.Failure -> sent.also {
        // KeyChangeError: the account holds a different key, set by another device in the meantime. Ours can never go up.
        if (it.error is AppError.Conflict) forgetPendingKeys()
      }
    }
  }

  /** The claim check's key material, kept so claiming does not spend a second rate-limited check. */
  private var lastClaim: Pair<String, ClaimInfoDto>? = null

  override val state: StateFlow<SessionState> = store.session
    .map { session ->
      cache.token = session?.token
      if (session == null) SessionState.SignedOut else SessionState.SignedIn(session)
    }
    .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

  override val keysLocked: StateFlow<Boolean> = combine(state, vault.unlockedFor, modes.mode) { st, unlockedFor, mode ->
    st is SessionState.SignedIn && mode == EncryptionMode.E2E && unlockedFor != st.session.user.id
  }.stateIn(scope, SharingStarted.Eagerly, false)

  private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  override val expired: SharedFlow<Unit> = _expired.asSharedFlow()

  init {
    // A refused token means the server ended the session (revocation, ban, or
    // expiry). Forget it locally so the app returns to the signed-out state.
    scope.launch {
      cache.unauthorized.collect { refused ->
        if (refused == cache.token) {
          store.clear()
          vault.clear()
          forgetPendingKeys()
          _expired.tryEmit(Unit)
        }
      }
    }
  }

  /**
   * What goes to the server as the password (API PR #114). For a split account it is the auth key, derived here
   * from the password and the account's salt; the password itself never leaves the phone. For an account made
   * before the split scheme it is the password, as it always was. The wrap key comes with it, for the private key.
   */
  private class Credential(val serverPassword: String, val split: SplitKeys?, val salt: String?, val params: JsonElement?) {
    val isSplit: Boolean get() = split != null
    fun wipe() = split?.wipe()
  }

  private suspend fun credential(username: String, password: String): ApiResult<Credential> {
    // Only a 404 means an older API, with no handshake at all and every account plain. A 200 with no body is not that:
    // it is a malformed answer, and it fails closed like any other (found by the iOS side's review, 22 Sep 2026).
    val olderApi: Boolean
    val params: LoginParamsDto? = when (val r = apiCall(json) { api.loginParams(username) }) {
      is ApiResult.Success -> { olderApi = false; r.value.data }
      is ApiResult.Failure -> if (r.error is AppError.NotFound) { olderApi = true; null } else return r
    }
    if (params?.isSplit == true) {
      val keys = engine.deriveSplit(password, params.kdfSalt!!, params.kdfParams!!)
      return ApiResult.Success(Credential(keys.authKey, keys, params.kdfSalt, params.kdfParams))
    }
    // Fail closed: "split" without its salt, a scheme this app has never heard of, or no answer at all, is not
    // "plain". Only a well-formed plain answer, or an older API, may send the password itself.
    if (!olderApi && params?.isWellFormed != true) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_scheme_refused))))
    // This phone has signed in to this name without sending the password. A server that now asks for the password
    // itself is not the server this account was made on, or has been tampered with. Nothing is sent.
    if (schemes.isKnownSplit(username)) return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_scheme_downgrade, username)))
    return ApiResult.Success(Credential(password, null, null, null))
  }

  /** Whether the server knows the split scheme at all. Null when it could not be asked. */
  private suspend fun splitSupported(username: String): ApiResult<Boolean> = when (val r = apiCall(json) { api.loginParams(username) }) {
    is ApiResult.Success -> ApiResult.Success(true)
    is ApiResult.Failure -> if (r.error is AppError.NotFound) ApiResult.Success(false) else r
  }

  /** A password the server accepted: the credential it was accepted in, and what sign-in answered. */
  private class Proof(val cred: Credential, val response: LoginData)

  /**
   * Proves a password to the server, the one way every proof goes: signing in, then deleting an account, then
   * confirming the current password before a change. Nothing is stored here. A refused auth key is the end of it:
   * the password itself goes only by [olderAccount], the person's explicit choice for an account from before.
   */
  private suspend fun prove(name: String, password: String, olderAccount: Boolean): ApiResult<Proof> {
    // The API decided (its brief, item 23): accounts are moved to split before the flag goes on, and a client never
    // sends the password on its own after a refused auth key. An account from before signs in only by the person's
    // explicit choice, and even then not a name this phone knows as split.
    val cred = if (olderAccount) {
      if (schemes.isKnownSplit(name)) return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_scheme_downgrade, name)))
      Credential(password, null, null, null)
    } else when (val c = credential(name, password)) { is ApiResult.Failure -> return c; is ApiResult.Success -> c.value }
    return when (val attempt = apiCall(json) { api.login(LoginRequest(name, cred.serverPassword)) }) {
      is ApiResult.Failure -> { cred.wipe(); attempt }
      // A success with no body is a malformed answer, not a session: say so, with the wrap key wiped like any other failure.
      is ApiResult.Success -> attempt.value.data?.let { ApiResult.Success(Proof(cred, it)) }
        ?: run { cred.wipe(); ApiResult.Failure(AppError.Unexpected(IllegalStateException("login response had no data"))) }
    }
  }

  override suspend fun login(username: String, password: String, olderAccount: Boolean): ApiResult<Session> {
    val name = username.trim()
    val proof = when (val p = prove(name, password, olderAccount)) { is ApiResult.Failure -> return p; is ApiResult.Success -> p.value }
    val used = proof.cred; val response = proof.response
    try {
      val session = response.toSession().copy(olderAccount = olderAccount)
      // Set the token for the interceptor now; the stored-session flow would only get there a moment later.
      cache.token = session.token
      store.save(session)
      if (used.isSplit) schemes.rememberSplit(name)
      if (modes.current() == EncryptionMode.E2E) prepareKeys(session, response.keys, password, used)
      return ApiResult.Success(session)
    } finally {
      used.wipe()
    }
  }

  /**
   * End-to-end mode, right after signing in. An account that has keys gets them
   * unwrapped with the password just typed. An account that has none (made
   * before the switch, or by an admin) gets a keypair now: generated here and
   * wrapped under the password and a new recovery code. The code is shown once,
   * and the keys are uploaded when the writer confirms they saved it
   * ([recoveryCodeSaved]); until then the account is signed in but locked. A
   * failure leaves it the same way, and the inbox offers to unlock.
   */
  private suspend fun prepareKeys(session: Session, bundle: KeyBundleDto?, password: String, cred: Credential) {
    val userId = session.user.id
    runCatching {
      if (bundle?.hasKeys == true) {
        // Split: the wrap key from this very sign-in opens it. Plain: the password does, as before.
        vault.store(userId, cred.split?.let { engine.unlockWithWrapKey(bundle.publicKey!!, bundle.wrappedPrivateKey!!, it.wrapKey) }
          ?: engine.unlockWithPassword(bundle.publicKey!!, bundle.wrappedPrivateKey!!, password, bundle.kdfSalt!!, bundle.kdfParams!!))
      } else {
        // A split account keeps its sign-in salt: the keys are wrapped under the wrap key of that same derivation.
        val fresh = cred.split?.let { engine.createAccountKeysUnderWrapKey(it.wrapKey, cred.salt!!, cred.params!!) } ?: engine.createAccountKeys(password)
        pendingKeys = PendingKeys(userId, fresh)
        _pendingRecoveryCode.value = fresh.recoveryCode
      }
    }
  }

  /** `caughtUp: { letters, sealed, dropped }`, or null once the server holds no keys of its own. Read leniently: it is only told. */
  private fun JsonElement?.caughtUpSealed(): Int =
    (((this as? JsonObject)?.get("caughtUp") as? JsonObject)?.get("sealed") as? JsonPrimitive)?.intOrNull ?: 0

  private fun AccountKeyFields.toRequest(includePublicKey: Boolean = true) = KeyFieldsRequest(
    publicKey = publicKey.takeIf { includePublicKey },
    wrappedPrivateKey = password.wrapped, kdfSalt = password.salt, kdfParams = password.params,
    recoveryWrappedPrivateKey = recovery.wrapped, recoverySalt = recovery.salt, recoveryKdfParams = recovery.params,
  )

  /**
   * Tells the server to end the token (so a stolen copy stops working), then
   * forgets it. If the server cannot be reached the local state is cleared
   * anyway: from the user's point of view they are signed out either way.
   */
  override suspend fun logout(everywhere: Boolean): ApiResult<Unit> {
    val result = apiCall(json) { api.logout(LogoutRequest(everywhere)) }.map { }
    store.clear()
    vault.clear()
    forgetPendingKeys()
    return result
  }

  override suspend fun claimInfo(token: String): ApiResult<ClaimInfo> =
    apiCall(json) { api.claimInfo(token) }.map { env ->
      val d = checkNotNull(env.data) { "claim response had no data" }
      lastClaim = token to d
      ClaimInfo(
        writerName = d.writer.name ?: "your account",
        groupName = d.chapter?.name,
        expiresAt = d.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        endToEnd = d.hasKeyMaterial,
      )
    }

  override suspend fun claim(token: String, username: String, password: String, email: String?, penName: String?): ApiResult<Session> {
    (state.value as? SessionState.SignedIn)?.let { return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.claim_signed_in, it.session.user.username))) }
    var request = ClaimRequest(token, username.trim(), password, email?.trim()?.ifBlank { null }, penName = penName?.let(PenName::normalise)?.ifBlank { null })
    var recoveryCode: String? = null
    // Every new account is split where the server knows the scheme (API PR #114).
    val split = when (val r = splitSupported(username.trim())) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    val material = lastClaim?.takeIf { it.first == token }?.second?.takeIf { it.hasKeyMaterial }
    if (material != null) {
      // End-to-end: the group made this keypair. Open it with the token, then re-wrap the very same
      // key under the new password and a new recovery code. Earlier letters stay readable because
      // the keypair does not change; the group's copy is deleted by the server on claim.
      val fresh = try {
        val keyPair = engine.unlockWithCode(material.publicKey!!, material.claimWrappedPrivateKey!!, token, material.claimSalt!!, material.claimKdfParams!!)
        if (split) engine.rewrapAllSplit(keyPair, password) else engine.rewrapAll(keyPair, password)
      } catch (e: Exception) {
        return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_token_wrong_key))))
      }
      recoveryCode = fresh.recoveryCode
      val f = fresh.fields
      request = request.copy(
        password = fresh.authKey ?: password, authScheme = SPLIT.takeIf { split },
        wrappedPrivateKey = f.password.wrapped, kdfSalt = f.password.salt, kdfParams = f.password.params,
        recoveryWrappedPrivateKey = f.recovery.wrapped, recoverySalt = f.recovery.salt, recoveryKdfParams = f.recovery.params,
      )
    } else if (split) {
      // No keys to wrap (server mode), but the password still never leaves the phone: an auth key under a fresh salt.
      val salt = engine.newSalt(); val params = engine.defaultParams()
      val keys = engine.deriveSplit(password, salt, json.encodeToJsonElement(KdfParams.serializer(), params))
      request = request.copy(password = keys.authKey, authScheme = SPLIT, kdfSalt = salt, kdfParams = params).also { keys.wipe() }
    }
    return when (val claimed = apiCall(json) { api.claim(request) }) {
      is ApiResult.Failure -> claimed
      is ApiResult.Success -> login(username, password).also { if (it is ApiResult.Success && recoveryCode != null) _pendingRecoveryCode.value = recoveryCode }
    }
  }

  override suspend fun joinInfo(code: String): ApiResult<Invitation> =
    apiCall(json) { api.joinInfo(code) }.map { env ->
      val d = checkNotNull(env.data) { "join response had no data" }
      Invitation(d.chapter.id, d.chapter.name ?: "", d.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() })
    }

  /**
   * An invite code makes an account that is the person's from the first request (API PR #116), so the keys are
   * made here, as for any new account: split wherever the server knows the scheme, with a keypair in end-to-end
   * mode and a salt and recipe alone in server mode. Then the ordinary sign-in, which the code has no part in.
   */
  override suspend fun join(code: String, username: String, password: String, email: String?, name: String?, penName: String?): ApiResult<Session> {
    // A new account must not replace a session unasked (a slip's link opened while signed in); the screen says so first.
    (state.value as? SessionState.SignedIn)?.let { return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.join_signed_in, it.session.user.username))) }
    val user = username.trim()
    var request = JoinRequest(code, user, password, email?.trim()?.ifBlank { null }, name?.trim()?.ifBlank { null }, penName = penName?.let(PenName::normalise)?.ifBlank { null })
    var recoveryCode: String? = null
    val split = when (val r = splitSupported(user)) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    if (modes.current() == EncryptionMode.E2E) {
      val fresh = if (split) engine.createAccountKeysSplit(password) else engine.createAccountKeys(password)
      recoveryCode = fresh.recoveryCode
      val f = fresh.fields
      request = request.copy(
        password = fresh.authKey ?: password, authScheme = SPLIT.takeIf { split },
        publicKey = f.publicKey, wrappedPrivateKey = f.password.wrapped, kdfSalt = f.password.salt, kdfParams = f.password.params,
        recoveryWrappedPrivateKey = f.recovery.wrapped, recoverySalt = f.recovery.salt, recoveryKdfParams = f.recovery.params,
      )
    } else if (split) {
      val salt = engine.newSalt(); val params = engine.defaultParams()
      val keys = engine.deriveSplit(password, salt, json.encodeToJsonElement(KdfParams.serializer(), params))
      request = request.copy(password = keys.authKey, authScheme = SPLIT, kdfSalt = salt, kdfParams = params).also { keys.wipe() }
    }
    return when (val joined = apiCall(json) { api.join(request) }) {
      is ApiResult.Failure -> joined
      is ApiResult.Success -> login(user, password).also { if (it is ApiResult.Success && recoveryCode != null) _pendingRecoveryCode.value = recoveryCode }
    }
  }

  override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
    val session = (state.value as? SessionState.SignedIn)?.session
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    // A session from before the app recorded its way learns it from its key first (see [settle]); nothing is sent for that.
    val settled = when (val s = settle(session, current)) {
      is ApiResult.Failure -> return s
      is ApiResult.Success -> s.value ?: return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_current_password_wrong))))
    }
    // The API does not ask for the current password, so confirm it by signing in with it, the session's own way. That
    // sign-in stores the session again, with a fresh token and the way it went, so what it answers is built on below.
    val proven = when (val check = login(session.user.username, current, olderAccount = settled.olderAccount ?: false)) {
      is ApiResult.Failure -> return if (check.error is AppError.Unauthorized) {
        // Still unknown (a server-mode account has no key to tell by): the refusal may be a mistyped password, or an
        // account from before that the old release signed in by its own fallback. Say both, and what to do.
        val why = if (settled.olderAccount == null) R.string.error_scheme_unknown_session else R.string.error_current_password_wrong
        ApiResult.Failure(AppError.Validation(listOf(strings.get(why))))
      } else {
        check
      }
      is ApiResult.Success -> check.value
    }
    // The new password goes split wherever the server knows the scheme: this is how an account made before it moves.
    val split = when (val r = splitSupported(session.user.username)) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    var request = UpdateUserRequest(id = session.user.id, password = new)
    if (modes.current() == EncryptionMode.E2E) {
      // The private key is wrapped under the password (or, split, under a key derived beside the auth key), so a new password means a new wrapping.
      val keyPair = vault.keyPair(session.user.id) ?: return ApiResult.Failure(lockedError(strings))
      if (split) {
        val (w, authKey) = engine.wrapForSplitPassword(keyPair, new)
        request = request.copy(password = authKey, authScheme = SPLIT, wrappedPrivateKey = w.wrapped, kdfSalt = w.salt, kdfParams = w.params)
      } else {
        val w = engine.wrapForPassword(keyPair, new)
        request = request.copy(wrappedPrivateKey = w.wrapped, kdfSalt = w.salt, kdfParams = w.params)
      }
    } else if (split) {
      val salt = engine.newSalt(); val params = engine.defaultParams()
      val keys = engine.deriveSplit(new, salt, json.encodeToJsonElement(KdfParams.serializer(), params))
      request = request.copy(password = keys.authKey, authScheme = SPLIT, kdfSalt = salt, kdfParams = params).also { keys.wipe() }
    }
    return when (val r = apiCall(json) { api.updateUser(request) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        if (split) schemes.rememberSplit(session.user.username)
        // The account is split from here on wherever the server knows the scheme: an account signed in as one from
        // before proves the auth key from now, not the password. Every older token (including the one just used) is
        // dead now; keep this device signed in.
        val moved = proven.copy(olderAccount = if (split) false else proven.olderAccount)
        store.save(r.value.data?.token?.let { moved.copy(token = it.token, expiresAtMillis = it.expires) } ?: moved)
        ApiResult.Success(Unit)
      }
    }
  }

  override suspend fun deleteAccount(password: String, wipe: suspend (userId: Int) -> Unit): ApiResult<DeletionReportDto> {
    val session = (state.value as? SessionState.SignedIn)?.session
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    // The phone proves the password before asking, as the iOS app does. An API build from before PR #104
    // ignores the password on this endpoint and deletes anyway (seen for real: a server that had not been
    // restarted since the merge). Signing in with it first means a wrong password can never delete anything,
    // whatever is on the other end. The token that sign-in issues is never stored; it goes with the account.
    // The same proof as signing in, the session's own way: an account signed in as one from before proves the
    // password itself, every other proves the auth key, and a refusal is the end of it. What the server accepted is
    // what it is asked to delete with. A session from before the app recorded its way learns it from its key first.
    val settled = when (val s = settle(session, password)) {
      is ApiResult.Failure -> return s
      is ApiResult.Success -> s.value ?: return ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_delete_wrong_password)))
    }
    val accepted = when (val p = prove(session.user.username, password, settled.olderAccount ?: false)) {
      is ApiResult.Failure -> return if (p.error is AppError.Unauthorized) {
        val why = if (settled.olderAccount == null) R.string.error_scheme_unknown_session else R.string.error_delete_wrong_password
        ApiResult.Failure(AppError.Forbidden(strings.get(why)))
      } else p
      is ApiResult.Success -> p.value.cred.also { it.wipe() }.serverPassword // only what the server checks is needed here
    }
    return when (val r = apiCall(json) { api.deleteUser(DeleteAccountRequest(session.user.id, accepted)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> withContext(NonCancellable) {
        // NonCancellable: the account is gone whatever happens next. A ViewModel scope cancelled half way
        // (the screens are rebuilt when the session goes) must not leave this person's letters on the phone.
        runCatching { wipe(session.user.id) }
        store.clear()
        vault.clear()
        forgetPendingKeys()
        ApiResult.Success(r.value.data ?: DeletionReportDto())
      }
    }
  }

  override suspend fun unlock(password: String): ApiResult<Unit> {
    val session = (state.value as? SessionState.SignedIn)?.session ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val bundle = when (val b = fetchKeys()) {
      is ApiResult.Failure -> return b
      // No key yet: sign in again, which checks the password with the server and makes the keys as a first sign-in does.
      is ApiResult.Success -> b.value ?: return when (val again = login(session.user.username, password, olderAccount = session.olderAccount == true)) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        // Refused: the server's bare "Unauthorized" is said the way this prompt says a wrong password.
        is ApiResult.Failure -> if (again.error is AppError.Unauthorized) ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_password_does_not_open)))) else again
      }
    }
    val (keyPair, older) = when (val o = openKeys(session, bundle, password)) {
      is ApiResult.Failure -> return o
      is ApiResult.Success -> o.value ?: return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_password_does_not_open))))
    }
    vault.store(session.user.id, keyPair)
    // A session from before the app recorded its way now knows it, from the key that opened.
    if (session.olderAccount == null) store.save(session.copy(olderAccount = older))
    return ApiResult.Success(Unit)
  }

  override suspend fun passwordStaysOnPhone(): Boolean {
    val session = (state.value as? SessionState.SignedIn)?.session ?: return false
    return session.olderAccount != true && schemes.isKnownSplit(session.user.username)
  }

  /** The account's key bundle, or null when it has no keys (server mode, or none made yet). */
  private suspend fun fetchKeys(): ApiResult<KeyBundleDto?> = when (val r = apiCall(json) { api.keys() }) {
    is ApiResult.Failure -> r
    is ApiResult.Success -> ApiResult.Success(r.value.data?.takeIf { it.hasKeys })
  }

  /**
   * Opens the private key with the password, the session's own way: the wrap key for a split account, the password
   * itself for one signed in as from before. A session that does not know its way ([Session.olderAccount] null,
   * saved by a release from before it was recorded) is tried both ways, the wrap key first, here on the phone: a wrong
   * guess sends nothing, and a name this phone knows as split is never tried the older way. Answers the key and the
   * way that opened it, or null when the password opens it no way it may be tried.
   */
  private suspend fun openKeys(session: Session, bundle: KeyBundleDto, password: String): ApiResult<Pair<Sodium.KeyPair, Boolean>?> {
    val name = session.user.username
    val ways = when (session.olderAccount) {
      true -> listOf(true)
      false -> listOf(false)
      null -> if (schemes.isKnownSplit(name)) listOf(false) else listOf(false, true)
    }
    for (older in ways) {
      val cred = if (older) Credential(password, null, null, null)
        else when (val c = credential(name, password)) { is ApiResult.Failure -> return c; is ApiResult.Success -> c.value }
      try {
        val keyPair = cred.split?.let { engine.unlockWithWrapKey(bundle.publicKey!!, bundle.wrappedPrivateKey!!, it.wrapKey) }
          ?: engine.unlockWithPassword(bundle.publicKey!!, bundle.wrappedPrivateKey!!, password, bundle.kdfSalt!!, bundle.kdfParams!!)
        return ApiResult.Success(keyPair to older)
      } catch (e: Exception) {
        if (!cred.isSplit) break // the password itself was just tried, and there is no other way left
      } finally {
        cred.wipe()
      }
    }
    return ApiResult.Success(null)
  }

  /**
   * A session from before the app recorded which way it signs in (Copilot's review of PR #4, 23 Sep 2026: DataStore
   * keeps sessions across updates, and the release before this one signed accounts from before in by a fallback of
   * its own) learns its way now, before a proof that would otherwise go the wrong way: the key bundle is fetched and
   * opened here, and the way that opened it is saved, with the key kept as an unlock keeps it. Nothing is sent. A
   * session that knows its way, a server-mode phone, and an account with no key have nothing to settle and pass
   * through as they are. Null when the password opens the key no way at all.
   */
  private suspend fun settle(session: Session, password: String): ApiResult<Session?> {
    if (session.olderAccount != null || modes.current() != EncryptionMode.E2E) return ApiResult.Success(session)
    val bundle = when (val b = fetchKeys()) { is ApiResult.Failure -> return b; is ApiResult.Success -> b.value ?: return ApiResult.Success(session) }
    val (keyPair, older) = when (val o = openKeys(session, bundle, password)) {
      is ApiResult.Failure -> return o
      is ApiResult.Success -> o.value ?: return ApiResult.Success(null)
    }
    vault.store(session.user.id, keyPair)
    return ApiResult.Success(session.copy(olderAccount = older).also { store.save(it) })
  }

  override suspend fun recover(username: String, recoveryCode: String, newPassword: String): ApiResult<Session> {
    val start = when (val r = apiCall(json) { api.recoverStart(username.trim()) }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> checkNotNull(r.value.data)
    }
    val keyPair = try {
      engine.unlockWithCode(start.publicKey, start.recoveryWrappedPrivateKey, recoveryCode, start.recoverySalt, start.recoveryKdfParams)
    } catch (e: Exception) {
      return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_recovery_code_wrong))))
    }
    // Opening the sealed challenge proves to the server that we hold the private key.
    val challenge = engine.openChallenge(start.sealedChallenge, keyPair)
    val split = when (val r = splitSupported(username.trim())) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    val finish = if (split) {
      val (w, authKey) = engine.wrapForSplitPassword(keyPair, newPassword)
      RecoverFinishRequest(username.trim(), challenge, authKey, w.wrapped, w.salt, w.params, authScheme = SPLIT)
    } else {
      val w = engine.wrapForPassword(keyPair, newPassword)
      RecoverFinishRequest(username.trim(), challenge, newPassword, w.wrapped, w.salt, w.params)
    }
    val finished = apiCall(json) { api.recoverFinish(finish) }
    return when (finished) {
      is ApiResult.Failure -> finished
      is ApiResult.Success -> login(username, newPassword)
    }
  }
}

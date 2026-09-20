package me.paxana.abcmailbox.data.session

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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.crypto.AccountKeyFields
import me.paxana.abcmailbox.data.api.ClaimInfoDto
import me.paxana.abcmailbox.data.api.ClaimRequest
import me.paxana.abcmailbox.data.api.KeyBundleDto
import me.paxana.abcmailbox.data.api.KeyFieldsRequest
import me.paxana.abcmailbox.data.api.RecoverFinishRequest
import me.paxana.abcmailbox.data.crypto.CryptoEngine
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.crypto.KeyVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.api.UpdateUserRequest
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.LoginRequest
import me.paxana.abcmailbox.data.api.LogoutRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.di.ApplicationScope
import me.paxana.abcmailbox.domain.ClaimInfo
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

  suspend fun login(username: String, password: String): ApiResult<Session>
  suspend fun logout(everywhere: Boolean = false): ApiResult<Unit>

  /** Who a claim token is for; the token must already be normalised. */
  suspend fun claimInfo(token: String): ApiResult<ClaimInfo>

  /** Claims the account, then signs in with the new credentials. */
  suspend fun claim(token: String, username: String, password: String, email: String?): ApiResult<Session>

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

  /** The writer confirmed they saved the code; forget it. */
  fun recoveryCodeSaved()

  /** True when signed in to an end-to-end server without the private key on this device. */
  val keysLocked: StateFlow<Boolean>

  /** Fetches the key bundle and unwraps it with the password. */
  suspend fun unlock(password: String): ApiResult<Unit>

  /** Recovery with the saved code: proves possession of the key, sets a new password, signs in. */
  suspend fun recover(username: String, recoveryCode: String, newPassword: String): ApiResult<Session>
}

/**
 * Lives as long as the process (a Hilt singleton) and does its bookkeeping on
 * an application-wide coroutine scope, because sign-in state must outlive any
 * one screen.
 */
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
) : SessionRepository {

  private val _pendingRecoveryCode = MutableStateFlow<String?>(null)
  override val pendingRecoveryCode: StateFlow<String?> = _pendingRecoveryCode.asStateFlow()
  override fun recoveryCodeSaved() { _pendingRecoveryCode.value = null }

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
          _expired.tryEmit(Unit)
        }
      }
    }
  }

  override suspend fun login(username: String, password: String): ApiResult<Session> {
    val response = when (val r = apiCall(json) { api.login(LoginRequest(username.trim(), password)) }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> checkNotNull(r.value.data) { "login response had no data" }
    }
    val session = response.toSession()
    // Set the token for the interceptor now; the stored-session flow would only get there a moment later.
    cache.token = session.token
    store.save(session)
    if (modes.current() == EncryptionMode.E2E) prepareKeys(session, response.keys, password)
    return ApiResult.Success(session)
  }

  /**
   * End-to-end mode, right after signing in. An account that has keys gets them
   * unwrapped with the password just typed. An account that has none (made
   * before the switch, or by an admin) gets a keypair now: generated here,
   * wrapped under the password and a new recovery code, and uploaded. The
   * recovery code is then shown once. A failure leaves the account signed in
   * but locked, and the inbox offers to unlock.
   */
  private suspend fun prepareKeys(session: Session, bundle: KeyBundleDto?, password: String) {
    val userId = session.user.id
    runCatching {
      if (bundle?.hasKeys == true) {
        vault.store(userId, engine.unlockWithPassword(bundle.publicKey!!, bundle.wrappedPrivateKey!!, password, bundle.kdfSalt!!, bundle.kdfParams!!))
      } else {
        val fresh = engine.createAccountKeys(password)
        val sent = apiCall(json) { api.putKeys(fresh.fields.toRequest()) }
        if (sent is ApiResult.Success) {
          vault.store(userId, fresh.keyPair)
          _pendingRecoveryCode.value = fresh.recoveryCode
        }
      }
    }
  }

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
    _pendingRecoveryCode.value = null
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

  override suspend fun claim(token: String, username: String, password: String, email: String?): ApiResult<Session> {
    var request = ClaimRequest(token, username.trim(), password, email?.trim()?.ifBlank { null })
    var recoveryCode: String? = null
    val material = lastClaim?.takeIf { it.first == token }?.second?.takeIf { it.hasKeyMaterial }
    if (material != null) {
      // End-to-end: the group made this keypair. Open it with the token, then re-wrap the very same
      // key under the new password and a new recovery code. Earlier letters stay readable because
      // the keypair does not change; the group's copy is deleted by the server on claim.
      val fresh = try {
        val keyPair = engine.unlockWithCode(material.publicKey!!, material.claimWrappedPrivateKey!!, token, material.claimSalt!!, material.claimKdfParams!!)
        engine.rewrapAll(keyPair, password)
      } catch (e: Exception) {
        return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_token_wrong_key))))
      }
      recoveryCode = fresh.recoveryCode
      val f = fresh.fields
      request = request.copy(
        wrappedPrivateKey = f.password.wrapped, kdfSalt = f.password.salt, kdfParams = f.password.params,
        recoveryWrappedPrivateKey = f.recovery.wrapped, recoverySalt = f.recovery.salt, recoveryKdfParams = f.recovery.params,
      )
    }
    return when (val claimed = apiCall(json) { api.claim(request) }) {
      is ApiResult.Failure -> claimed
      is ApiResult.Success -> login(username, password).also { if (it is ApiResult.Success && recoveryCode != null) _pendingRecoveryCode.value = recoveryCode }
    }
  }

  override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
    val session = (state.value as? SessionState.SignedIn)?.session
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    // The API does not ask for the current password, so confirm it by signing in with it.
    when (val check = apiCall(json) { api.login(LoginRequest(session.user.username, current)) }) {
      is ApiResult.Failure -> return if (check.error is AppError.Unauthorized) {
        ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_current_password_wrong))))
      } else {
        check
      }
      is ApiResult.Success -> Unit
    }
    var request = UpdateUserRequest(id = session.user.id, password = new)
    if (modes.current() == EncryptionMode.E2E) {
      // The private key is wrapped under the password, so a new password means a new wrapping.
      val keyPair = vault.keyPair(session.user.id) ?: return ApiResult.Failure(lockedError(strings))
      val w = engine.wrapForPassword(keyPair, new)
      request = request.copy(wrappedPrivateKey = w.wrapped, kdfSalt = w.salt, kdfParams = w.params)
    }
    return when (val r = apiCall(json) { api.updateUser(request) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        // Every older token (including the one just used) is dead now; keep this device signed in.
        r.value.data?.token?.let { store.save(session.copy(token = it.token, expiresAtMillis = it.expires)) }
        ApiResult.Success(Unit)
      }
    }
  }

  override suspend fun deleteAccount(password: String, wipe: suspend (userId: Int) -> Unit): ApiResult<DeletionReportDto> {
    val session = (state.value as? SessionState.SignedIn)?.session
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    return when (val r = apiCall(json) { api.deleteUser(DeleteAccountRequest(session.user.id, password)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> withContext(NonCancellable) {
        // NonCancellable: the account is gone whatever happens next. A ViewModel scope cancelled half way
        // (the screens are rebuilt when the session goes) must not leave this person's letters on the phone.
        runCatching { wipe(session.user.id) }
        store.clear()
        vault.clear()
        _pendingRecoveryCode.value = null
        ApiResult.Success(r.value.data ?: DeletionReportDto())
      }
    }
  }

  override suspend fun unlock(password: String): ApiResult<Unit> {
    val session = (state.value as? SessionState.SignedIn)?.session ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val bundle = when (val r = apiCall(json) { api.keys() }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> r.value.data
    }
    if (bundle?.hasKeys != true) return ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_no_keys_yet))))
    return try {
      vault.store(session.user.id, engine.unlockWithPassword(bundle.publicKey!!, bundle.wrappedPrivateKey!!, password, bundle.kdfSalt!!, bundle.kdfParams!!))
      ApiResult.Success(Unit)
    } catch (e: Exception) {
      ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.error_password_does_not_open))))
    }
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
    val w = engine.wrapForPassword(keyPair, newPassword)
    val finished = apiCall(json) { api.recoverFinish(RecoverFinishRequest(username.trim(), challenge, newPassword, w.wrapped, w.salt, w.params)) }
    return when (finished) {
      is ApiResult.Failure -> finished
      is ApiResult.Success -> login(username, newPassword)
    }
  }
}

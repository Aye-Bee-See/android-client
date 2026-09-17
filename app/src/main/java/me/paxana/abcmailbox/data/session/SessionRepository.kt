package me.paxana.abcmailbox.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
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
import me.paxana.abcmailbox.data.api.ClaimRequest
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
  @ApplicationScope private val scope: CoroutineScope,
) : SessionRepository {

  override val state: StateFlow<SessionState> = store.session
    .map { session ->
      cache.token = session?.token
      if (session == null) SessionState.SignedOut else SessionState.SignedIn(session)
    }
    .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

  private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  override val expired: SharedFlow<Unit> = _expired.asSharedFlow()

  init {
    // A refused token means the server ended the session (revocation, ban, or
    // expiry). Forget it locally so the app returns to the signed-out state.
    scope.launch {
      cache.unauthorized.collect { refused ->
        if (refused == cache.token) {
          store.clear()
          _expired.tryEmit(Unit)
        }
      }
    }
  }

  override suspend fun login(username: String, password: String): ApiResult<Session> {
    val result = apiCall(json) { api.login(LoginRequest(username.trim(), password)) }
      .map { envelope -> checkNotNull(envelope.data) { "login response had no data" }.toSession() }
    if (result is ApiResult.Success) store.save(result.value)
    return result
  }

  /**
   * Tells the server to end the token (so a stolen copy stops working), then
   * forgets it. If the server cannot be reached the local state is cleared
   * anyway: from the user's point of view they are signed out either way.
   */
  override suspend fun logout(everywhere: Boolean): ApiResult<Unit> {
    val result = apiCall(json) { api.logout(LogoutRequest(everywhere)) }.map { }
    store.clear()
    return result
  }

  override suspend fun claimInfo(token: String): ApiResult<ClaimInfo> =
    apiCall(json) { api.claimInfo(token) }.map { env ->
      val d = checkNotNull(env.data) { "claim response had no data" }
      ClaimInfo(
        writerName = d.writer.name ?: "your account",
        groupName = d.chapter?.name,
        expiresAt = d.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
      )
    }

  override suspend fun claim(token: String, username: String, password: String, email: String?): ApiResult<Session> {
    val claimed = apiCall(json) { api.claim(ClaimRequest(token, username.trim(), password, email?.trim()?.ifBlank { null })) }
    return when (claimed) {
      is ApiResult.Failure -> claimed
      is ApiResult.Success -> login(username, password)
    }
  }

  override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
    val session = (state.value as? SessionState.SignedIn)?.session
      ?: return ApiResult.Failure(AppError.Unauthorized("You are signed out."))
    // The API does not ask for the current password, so confirm it by signing in with it.
    when (val check = apiCall(json) { api.login(LoginRequest(session.user.username, current)) }) {
      is ApiResult.Failure -> return if (check.error is AppError.Unauthorized) {
        ApiResult.Failure(AppError.Validation(listOf("Your current password is incorrect.")))
      } else {
        check
      }
      is ApiResult.Success -> Unit
    }
    return when (val r = apiCall(json) { api.updateUser(UpdateUserRequest(id = session.user.id, password = new)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        // Every older token (including the one just used) is dead now; keep this device signed in.
        r.value.data?.token?.let { store.save(session.copy(token = it.token, expiresAtMillis = it.expires)) }
        ApiResult.Success(Unit)
      }
    }
  }
}

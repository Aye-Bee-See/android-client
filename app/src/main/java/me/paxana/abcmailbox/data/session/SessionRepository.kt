package me.paxana.abcmailbox.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.LoginRequest
import me.paxana.abcmailbox.data.api.LogoutRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that signs in and out. Screens observe [state]; nothing else
 * touches the token. ViewModels depend on this interface so tests can hand
 * them a scripted fake; Hilt binds [DefaultSessionRepository] in the app.
 */
interface SessionRepository {
  val state: StateFlow<SessionState>
  suspend fun login(username: String, password: String): ApiResult<Session>
  suspend fun logout(everywhere: Boolean = false): ApiResult<Unit>
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

  init {
    // A refused token means the server ended the session (revocation, ban, or
    // expiry). Forget it locally so the app returns to the signed-out state.
    scope.launch {
      cache.unauthorized.collect { refused ->
        if (refused == cache.token) store.clear()
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
}

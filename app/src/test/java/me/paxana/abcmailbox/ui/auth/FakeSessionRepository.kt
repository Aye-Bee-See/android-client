package me.paxana.abcmailbox.ui.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser

/** A scripted [SessionRepository]: fails with [nextError] when set, otherwise signs in. */
class FakeSessionRepository(private val nextError: AppError? = null) : SessionRepository {

  val attempts = mutableListOf<Pair<String, String>>()
  private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
  override val state: StateFlow<SessionState> = _state

  override suspend fun login(username: String, password: String): ApiResult<Session> {
    attempts += username to password
    nextError?.let { return ApiResult.Failure(it) }
    val session = Session("tok", 0L, SessionUser(1, username, null, null, "user", null))
    _state.value = SessionState.SignedIn(session)
    return ApiResult.Success(session)
  }

  override suspend fun logout(everywhere: Boolean): ApiResult<Unit> {
    _state.value = SessionState.SignedOut
    return ApiResult.Success(Unit)
  }
}

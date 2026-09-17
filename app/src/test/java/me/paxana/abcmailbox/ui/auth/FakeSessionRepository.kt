package me.paxana.abcmailbox.ui.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.ClaimInfo
import java.time.Instant

/** A scripted [SessionRepository]: fails with [nextError] when set, otherwise signs in. */
class FakeSessionRepository(
  private val nextError: AppError? = null,
  private val claimInfoError: AppError? = null,
  private val currentPassword: String = "password1",
) : SessionRepository {

  val attempts = mutableListOf<Pair<String, String>>()
  val claimChecks = mutableListOf<String>()
  val claims = mutableListOf<List<String?>>()
  var passwordChangedTo: String? = null
  override val expired = MutableSharedFlow<Unit>()
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

  override suspend fun claimInfo(token: String): ApiResult<ClaimInfo> {
    claimChecks += token
    claimInfoError?.let { return ApiResult.Failure(it) }
    return ApiResult.Success(ClaimInfo("Alex", "Test Chapter", Instant.parse("2026-09-20T00:00:00Z")))
  }

  override suspend fun claim(token: String, username: String, password: String, email: String?): ApiResult<Session> {
    claims += listOf(token, username, password, email)
    nextError?.let { return ApiResult.Failure(it) }
    return login(username, password)
  }

  override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
    if (current != currentPassword) return ApiResult.Failure(AppError.Validation(listOf("Your current password is incorrect.")))
    passwordChangedTo = new
    return ApiResult.Success(Unit)
  }
}

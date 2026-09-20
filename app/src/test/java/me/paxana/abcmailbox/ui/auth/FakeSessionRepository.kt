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

  /** Tests that need a particular account (a group member, say) skip the sign-in form. */
  fun signInAs(user: SessionUser) { _state.value = SessionState.SignedIn(Session("tok", 0L, user)) }

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

  override val pendingRecoveryCode = MutableStateFlow<String?>(null)
  override fun recoveryCodeSaved() { pendingRecoveryCode.value = null }
  override val keysLocked = MutableStateFlow(false)
  var unlockPassword: String = "password1"
  val recoveries = mutableListOf<Triple<String, String, String>>()

  override suspend fun unlock(password: String): ApiResult<Unit> =
    if (password == unlockPassword) { keysLocked.value = false; ApiResult.Success(Unit) }
    else ApiResult.Failure(AppError.Validation(listOf("That password does not open your letters.")))

  override suspend fun recover(username: String, recoveryCode: String, newPassword: String): ApiResult<Session> {
    recoveries += Triple(username, recoveryCode, newPassword)
    nextError?.let { return ApiResult.Failure(it) }
    return login(username, newPassword)
  }

  /** Set to make the next deletion fail the way the server would. */
  var deleteError: AppError? = null
  var deleteReport = me.paxana.abcmailbox.data.api.DeletionReportDto(deleted = 1, letters = 3, replies = 1, attachments = 1, threads = 2)
  override suspend fun deleteAccount(password: String, wipe: suspend (userId: Int) -> Unit): ApiResult<me.paxana.abcmailbox.data.api.DeletionReportDto> {
    val me = (_state.value as? SessionState.SignedIn)?.session?.user ?: return ApiResult.Failure(AppError.Unauthorized(null))
    deleteError?.let { return ApiResult.Failure(it) }
    if (password != currentPassword) return ApiResult.Failure(AppError.Forbidden("The password is wrong; nothing was deleted."))
    wipe(me.id)
    _state.value = SessionState.SignedOut
    return ApiResult.Success(deleteReport)
  }

  override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
    if (current != currentPassword) return ApiResult.Failure(AppError.Validation(listOf("Your current password is incorrect.")))
    passwordChangedTo = new
    return ApiResult.Success(Unit)
  }
}

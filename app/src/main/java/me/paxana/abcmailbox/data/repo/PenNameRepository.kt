package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.UpdateUserRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import kotlinx.coroutines.flow.first
import me.paxana.abcmailbox.data.session.SessionStore
import me.paxana.abcmailbox.domain.PenName
import me.paxana.abcmailbox.domain.PenNameCheck
import me.paxana.abcmailbox.domain.PenNameRow
import me.paxana.abcmailbox.domain.PenNames
import me.paxana.abcmailbox.text.Strings
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Pen names (API PR #120): is a name free, the account's names, and a change of name. */
interface PenNameRepository {
  /** The public check, for a form as the person types; the shape is checked on the phone first ([PenName.problem]). */
  suspend fun check(name: String): ApiResult<PenNameCheck>

  /** The signed-in account's names, current first. */
  suspend fun names(): ApiResult<PenNames>

  /** A new pen name for the signed-in account; the session's copy of the user follows. Answers the name as stored. */
  suspend fun set(name: String): ApiResult<String>
}

@Singleton
class DefaultPenNameRepository @Inject constructor(
  private val api: AuthApi,
  private val store: SessionStore,
  private val json: Json,
  private val strings: Strings,
) : PenNameRepository {

  override suspend fun check(name: String): ApiResult<PenNameCheck> {
    val n = PenName.normalise(name)
    return apiCall(json) { api.penNameAvailable(n) }.map { env ->
      val d = checkNotNull(env.data) { "pen-name-available response had no data" }
      PenNameCheck(d.name?.takeIf { it.isNotBlank() } ?: n, d.available, d.reason, d.twoParts)
    }
  }

  override suspend fun names(): ApiResult<PenNames> = apiCall(json) { api.penNames() }.map { env ->
    val d = checkNotNull(env.data) { "pen-name response had no data" }
    PenNames(d.penName?.takeIf { it.isNotBlank() }, d.names.map { PenNameRow(it.name, it.current, it.since?.let { s -> runCatching { Instant.parse(s) }.getOrNull() }) })
  }

  override suspend fun set(name: String): ApiResult<String> {
    // The stored session is the one that is updated, so it is the one read: the state flow is derived from it.
    val session = store.session.first()
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val n = PenName.normalise(name)
    return when (val r = apiCall(json) { api.updateUser(UpdateUserRequest(id = session.user.id, penName = n)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        // The session's user is what the account page shows; the old name stays the account's on the server.
        store.save(session.copy(user = session.user.copy(penName = n)))
        ApiResult.Success(n)
      }
    }
  }
}

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

  /** The signed-in account's names, current first, and what the limits leave it (API #127). */
  suspend fun names(): ApiResult<PenNames>

  /**
   * A new pen name for the signed-in account; the session's copy of the user follows. Answers the name as stored. A
   * change the limits refuse is `409 PenNameLimitError` with `condition` `cooldown` or `new_names` ([penNameLimit]).
   */
  suspend fun set(name: String): ApiResult<String>
}

/** A pen name change the limits refused (API #127): `cooldown` or `new_names`, or null for any other error. */
val AppError.penNameLimit: String? get() = (this as? AppError.Conflict)?.takeIf { it.name == "PenNameLimitError" }?.let { it.condition ?: "cooldown" }

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
    fun instant(s: String?) = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val newPerYear = d.newPerYear ?: 2
    PenNames(
      d.penName?.takeIf { it.isNotBlank() }, d.names.map { PenNameRow(it.name, it.current, instant(it.since)) },
      changeAllowedAt = instant(d.changeAllowedAt), newNamesLeft = d.newNamesLeft ?: newPerYear, newNamesWindowEnds = instant(d.newNamesWindowEnds),
      cooldownDays = d.cooldownDays ?: 90, newPerYear = newPerYear,
    )
  }

  override suspend fun set(name: String): ApiResult<String> {
    // The stored session is the one that is updated, so it is the one read: the state flow is derived from it.
    val session = store.session.first()
      ?: return ApiResult.Failure(AppError.Unauthorized(strings.get(R.string.error_signed_out)))
    val n = PenName.normalise(name)
    return when (val r = apiCall(json) { api.updateUser(UpdateUserRequest(id = session.user.id, penName = n)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        // Going back to an old name takes it in the spelling it was first given, which may not be what was typed
        // ("anna hollow" for "Anna Hollow"); the answer does not say, so the names are read back. Typed, if they cannot be.
        val stored = (names() as? ApiResult.Success)?.value?.current ?: n
        // The session's user is what the account page shows; the old name stays the account's on the server.
        store.save(session.copy(user = session.user.copy(penName = stored)))
        ApiResult.Success(stored)
      }
    }
  }
}

package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.CancelInviteCodesRequest
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.InviteBatchDto
import me.paxana.abcmailbox.data.api.IssueInviteCodesRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.InviteBatch
import me.paxana.abcmailbox.domain.InviteQuota
import me.paxana.abcmailbox.domain.IssuedInvites
import me.paxana.abcmailbox.text.Strings
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A group's invite codes (API PR #116): issuing a batch to print as slips, the standing list with its counts,
 * and cancelling what was not used. For the signed-in group admin's own group; a superadmin, who would have to
 * name a group, is not served here (the website does that).
 */
interface InviteRepository {
  suspend fun quota(): ApiResult<InviteQuota>

  /** Issues [count] codes (1 to 50). The answer is the only time the server says them; it is kept on the phone until [finished]. */
  suspend fun issue(count: Int, label: String?, days: Int?): ApiResult<IssuedInvites>

  /** A batch issued and not yet printed or saved, kept across a process death. Null when there is none. */
  suspend fun pending(): IssuedInvites?

  /** The person has the slips: the codes leave the phone. */
  suspend fun finished()

  /** Cancels the unused codes of one batch, or of every batch when [batch] is null. Answers how many were cancelled. */
  suspend fun cancel(batch: String?): ApiResult<Int>

  companion object {
    const val COUNT_MAX = 50
    const val LABEL_MAX = 80
  }
}

@Singleton
class DefaultInviteRepository @Inject constructor(
  private val api: GroupApi,
  private val sessions: SessionRepository,
  private val json: Json,
  private val strings: Strings,
  private val pending: PendingInvitesStore,
) : InviteRepository {

  private val groupId: Int? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.chapterId
  private fun notInGroup() = ApiResult.Failure(AppError.Forbidden(strings.get(R.string.error_not_in_group)))

  override suspend fun quota(): ApiResult<InviteQuota> {
    if (groupId == null) return notInGroup()
    return apiCall(json) { api.inviteCodes() }.map { env ->
      val d = checkNotNull(env.data) { "invite-codes response had no data" }
      InviteQuota(d.outstanding, d.limit, d.batches.map { it.toDomain() })
    }
  }

  override suspend fun issue(count: Int, label: String?, days: Int?): ApiResult<IssuedInvites> {
    val id = groupId ?: return notInGroup()
    // The slips carry the group's name. It is read first, so that nothing is issued that could not be printed.
    val groupName = when (val g = apiCall(json) { api.ownGroup(id) }) {
      is ApiResult.Failure -> return g
      is ApiResult.Success -> checkNotNull(g.value.data) { "chapter response had no data" }.name
    }
    val request = IssueInviteCodesRequest(count = count, label = label?.trim()?.ifBlank { null }, days = days)
    return when (val r = apiCall(json) { api.issueInviteCodes(request) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        val d = checkNotNull(r.value.data) { "invite-codes response had no data" }
        val issued = IssuedInvites(d.batch, d.label, d.expiresAt?.toInstant(), d.codes, groupName, d.outstanding, d.limit)
        // Kept before it is answered: from here the codes survive whatever happens to the process.
        pending.save(issued)
        ApiResult.Success(issued)
      }
    }
  }

  override suspend fun pending(): IssuedInvites? = pending.load()
  override suspend fun finished() = pending.clear()

  override suspend fun cancel(batch: String?): ApiResult<Int> {
    if (groupId == null) return notInGroup()
    val request = if (batch == null) CancelInviteCodesRequest(all = true) else CancelInviteCodesRequest(batch = batch)
    return apiCall(json) { api.cancelInviteCodes(request) }.map { env -> env.data?.cancelled ?: 0 }
  }

  private fun InviteBatchDto.toDomain() = InviteBatch(batch, label, createdAt?.toInstant(), expiresAt?.toInstant(), total, used, unused, cancelled, expired)
  private fun String.toInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
}

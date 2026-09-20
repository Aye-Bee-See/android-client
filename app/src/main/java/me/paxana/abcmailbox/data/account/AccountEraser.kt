package me.paxana.abcmailbox.data.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import me.paxana.abcmailbox.data.activity.ActivityRepository
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.push.PushRegistrar
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.OutboxRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What went, for the receipt shown afterwards. The first four are the server's count; the last is this phone's. */
data class DeletionReport(val letters: Int, val replies: Int, val attachments: Int, val threads: Int, val unsentOnPhone: Int)

/** What would go, shown before anyone is asked for a password. `threads` is null when the server could not be asked. */
data class DeletionPreview(val threads: Int?, val unsentOnPhone: Int)

/**
 * Deleting one's account: the server's part, and then this phone's.
 *
 * Signing out deliberately keeps things (unsent letters wait for the person to come back; drafts too).
 * Deleting keeps nothing that was theirs: unsent letters and their files, drafts, attachments opened for
 * reading, the push registration, their place in the notification feed, their keys, the session. What stays
 * is what was never theirs: the offline copy of the public directory, fetched anonymously.
 *
 * A class of its own because every one of those stores depends on [SessionRepository], so the session
 * repository cannot call them. It hands this class the moment instead (`wipe`).
 */
interface AccountEraser {
  /** The receipt, until the person has seen it. Held here, not in a screen: every screen is rebuilt when the session goes. */
  val farewell: StateFlow<DeletionReport?>
  fun farewellSeen()
  suspend fun preview(): DeletionPreview
  suspend fun delete(password: String): ApiResult<DeletionReport>
}

@Singleton
class DefaultAccountEraser @Inject constructor(
  private val sessions: SessionRepository,
  private val letters: LettersRepository,
  private val outbox: OutboxRepository,
  private val drafts: DraftsRepository,
  private val files: LocalFilesContract,
  private val push: PushRegistrar,
  private val activity: ActivityRepository,
) : AccountEraser {

  private val _farewell = MutableStateFlow<DeletionReport?>(null)
  override val farewell: StateFlow<DeletionReport?> = _farewell.asStateFlow()
  override fun farewellSeen() { _farewell.value = null }

  override suspend fun preview() = DeletionPreview(threads = letters.threadCount(), unsentOnPhone = outbox.items().first().size)

  override suspend fun delete(password: String): ApiResult<DeletionReport> {
    var unsent = 0
    val result = sessions.deleteAccount(password) { userId ->
      // Each on its own: one store failing must not spare the others.
      runCatching { unsent = outbox.eraseFor(userId) }
      runCatching { drafts.eraseFor(userId) }
      runCatching { files.emptyCaches() }
      runCatching { push.forgetLocally() }
      runCatching { activity.forget(userId) }
    }
    return when (result) {
      is ApiResult.Failure -> result
      is ApiResult.Success -> {
        val r = result.value
        val report = DeletionReport(r.letters, r.replies, r.attachments, r.threads, unsent)
        _farewell.value = report
        ApiResult.Success(report)
      }
    }
  }
}

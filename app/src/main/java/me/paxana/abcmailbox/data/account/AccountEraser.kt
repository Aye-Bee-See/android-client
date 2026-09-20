package me.paxana.abcmailbox.data.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import me.paxana.abcmailbox.data.activity.ActivityRepository
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.data.session.Role
import me.paxana.abcmailbox.data.session.SessionState
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

/**
 * What the person should know before deleting, gathered best effort. Nothing here blocks the delete: the
 * server decides. It is so that the page can say in numbers what is about to go, and can explain a refusal
 * before the person has typed anything instead of after. (The same shape as the iOS app's preview.)
 */
data class DeletionPreview(
  /** A writer's conversations. Null for a group member, whose list is the group's and stays; and when the server could not be asked. */
  val threads: Int? = null,
  val unsentOnPhone: Int = 0,
  val endToEnd: Boolean = false,
  /** End-to-end mode: this member is the only one holding their group's key. The server will refuse (409), or the group could never read its letters again. */
  val isLastKeyHolder: Boolean = false,
  /** Members who have a key of their own and could be handed the group's first. */
  val membersWhoCouldHoldTheKey: List<String> = emptyList(),
)

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
  private val modes: EncryptionModeRepository,
  private val group: GroupRepository,
) : AccountEraser {

  private val _farewell = MutableStateFlow<DeletionReport?>(null)
  override val farewell: StateFlow<DeletionReport?> = _farewell.asStateFlow()
  override fun farewellSeen() { _farewell.value = null }

  override suspend fun preview(): DeletionPreview {
    val user = (sessions.state.value as? SessionState.SignedIn)?.session?.user ?: return DeletionPreview()
    val endToEnd = modes.current() == EncryptionMode.E2E
    var preview = DeletionPreview(
      // A writer's conversations are all theirs. A group member's list is the group's, which stays, so no number is shown.
      threads = if (user.isStaff) null else letters.threadCount(),
      unsentOnPhone = outbox.items().first().size,
      endToEnd = endToEnd,
    )
    if (endToEnd && user.role == Role.CHAPTER) {
      val members = (group.members() as? ApiResult.Success)?.value.orEmpty()
      if (members.any { it.isMe && it.holdsGroupKey }) preview = preview.copy(
        isLastKeyHolder = members.none { !it.isMe && it.holdsGroupKey },
        membersWhoCouldHoldTheKey = members.filter { !it.isMe && it.hasOwnKey && !it.holdsGroupKey }.map { it.name },
      )
    }
    return preview
  }

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

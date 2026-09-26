package me.paxana.abcmailbox.ui.nav

import kotlinx.serialization.Serializable

/**
 * Navigation destinations as Kotlin objects instead of strings. Navigation
 * Compose serialises them into the back stack; a typo becomes a compile error
 * and arguments become constructor parameters.
 *
 * The Directory tab is a nested graph: its detail screens live inside it, so
 * the tab stays selected while you drill down and each tab keeps its own
 * back stack.
 */
@Serializable data object DirectoryGraph
@Serializable data object DirectoryHomeRoute
@Serializable data object PrisonersRoute
@Serializable data class PrisonerRoute(val id: Int)
@Serializable data object FacilitiesRoute
@Serializable data class FacilityRoute(val id: Int)
@Serializable data object GroupsRoute
@Serializable data class GroupRoute(val id: Int)

@Serializable data object InboxGraph
@Serializable data object InboxRoute
@Serializable data class ThreadRoute(val chatId: Int)
/** `editMessageId` set means "edit this queued letter" instead of "write a new one". */
@Serializable data class ComposeRoute(
  val prisonerId: Int,
  val editMessageId: Int? = null,
  /** Group accounts: the managed writer this letter is from. Null means anonymous for a group, or yourself for a writer. */
  val writerId: Int? = null,
  val writerName: String? = null,
  /** Group accounts: record a prisoner's reply on this writer's thread instead of writing a letter. */
  val replyForUserId: Int? = null,
  /** Reopening a letter from the outbox: compose starts from it, and sending (or queueing) again replaces it. */
  val outboxId: Long? = null,
  /** Sending again a letter that came back: compose starts from its text, and the new letter names it (`resendOf`). */
  val resendOf: Int? = null,
  /** Sending again a held letter that must be sealed for another group: compose starts from its text, and the old one is removed once the new one is sent. */
  val replacesHeld: Int? = null,
  /** Recording a reply by the number the prisoner copied (API PR #120): sent with the reply, so the server files it and names the letter answered. */
  val reference: String? = null,
)
/** `replyFor`: the prisoner picked is who wrote back to this writer, and compose records a reply (API PR #120, a reply filed by name). */
@Serializable data class PickPrisonerRoute(val writerId: Int? = null, val writerName: String? = null, val replyFor: Boolean = false)
/** A reply filed by its reference (API PR #120), or nothing. */
@Serializable data object RecordReplyRoute
@Serializable data object PenNameRoute

// Group member screens
@Serializable data class LetterWorkRoute(val messageId: Int)
@Serializable data object AddWriterRoute
@Serializable data object GroupNumbersRoute
@Serializable data class HandoffRoute(val writerId: Int, val writerName: String)
/** End-to-end servers: who in the group holds its key. */
@Serializable data object GroupKeyRoute
@Serializable data object AccountRoute
@Serializable data object LoginRoute
/** `token` is set when the screen was opened by a claim link. */
@Serializable data class ClaimRoute(val token: String? = null)
/** `code` is set when the screen was opened by a slip's link (API PR #116). */
@Serializable data class JoinRoute(val code: String? = null)
/** `token` is set when the join screen handed on a 24-character token it was given. */
@Serializable data class InvitationRoute(val token: String? = null)
@Serializable data object InviteCodesRoute
@Serializable data object RecoverRoute
@Serializable data object ChangePasswordRoute
@Serializable data object DeleteAccountRoute
/** Full screen, not dismissible: the one-time recovery code. The code itself never goes through navigation state. */
@Serializable data object RecoveryCodeRoute

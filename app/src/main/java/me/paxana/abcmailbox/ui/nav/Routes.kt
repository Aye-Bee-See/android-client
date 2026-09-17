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
@Serializable data class ComposeRoute(val prisonerId: Int, val editMessageId: Int? = null)
@Serializable data object PickPrisonerRoute
@Serializable data object AccountRoute
@Serializable data object LoginRoute
/** `token` is set when the screen was opened by a claim link. */
@Serializable data class ClaimRoute(val token: String? = null)
@Serializable data object RecoverRoute
@Serializable data object ChangePasswordRoute

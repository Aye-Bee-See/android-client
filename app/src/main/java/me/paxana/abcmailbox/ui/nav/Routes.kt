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

@Serializable data object InboxRoute
@Serializable data object AccountRoute
@Serializable data object LoginRoute

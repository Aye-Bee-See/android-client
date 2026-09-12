package me.paxana.abcmailbox.ui.nav

import kotlinx.serialization.Serializable

/**
 * Navigation destinations as Kotlin objects instead of strings. Navigation
 * Compose serialises them into the back stack; a typo becomes a compile error
 * and arguments become constructor parameters.
 */
@Serializable data object DirectoryRoute
@Serializable data object InboxRoute
@Serializable data object AccountRoute
@Serializable data object LoginRoute

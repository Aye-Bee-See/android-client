package me.paxana.abcmailbox.domain

import java.time.Instant

/** An account a group created for someone who has not claimed it yet. */
data class ManagedWriter(
  val id: Int,
  val name: String,
  /** Null when the address is the API's `…@managed.example` placeholder. */
  val email: String?,
  val note: String?,
  /** When a live claim token expires, or null when there is none. */
  val tokenExpiresAt: Instant?,
) {
  val hasLiveToken: Boolean get() = tokenExpiresAt?.isAfter(Instant.now()) == true
}

data class IssuedToken(val token: String, val expiresAt: Instant?)

/** A letter in the group's queue, with who it goes to: what a volunteer needs to address the envelope. */
data class QueueItem(val letter: Letter, val prisoner: Prisoner?)

/** Who is on the writer's side of a thread, as far as a group needs to know. */
data class ThreadWriter(val id: Int, val name: String, val managedByGroupId: Int?, val anonymousForGroupId: Int?) {
  /** A group may write in a thread only for writers it manages, or as its own anonymous writer. */
  fun canBeWrittenForBy(groupId: Int?): Boolean = groupId != null && (managedByGroupId == groupId || anonymousForGroupId == groupId)
  val label: String get() = if (anonymousForGroupId != null) "Anonymous writer" else name
}

package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
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

/** Someone in the group, and whether they can open letters sealed to it. */
data class GroupMember(
  val id: Int,
  val name: String,
  /** False until their first sign-in on an end-to-end server, when their own keypair is made. Nothing can be sealed to them before that. */
  val hasOwnKey: Boolean,
  val holdsGroupKey: Boolean,
  val isMe: Boolean,
  /** The chapter's group-owner admin: the one who hands the key out, takes it back and passes the role on (API PR #115). */
  val isOwner: Boolean = false,
  /** Has keys of their own and is still to be handed the chapter's. */
  val isWaiting: Boolean = false,
)

/** What a transfer of ownership answered. */
data class OwnerChange(val newOwnerId: Int, val holdsGroupKey: Boolean)

data class IssuedToken(val token: String, val expiresAt: Instant?)

/** A letter in the group's queue, with who it goes to: what a volunteer needs to address the envelope. */
data class QueueItem(val letter: Letter, val prisoner: Prisoner?)

/** Who is on the writer's side of a thread, as far as a group needs to know. */
data class ThreadWriter(val id: Int, val name: String, val managedByGroupId: Int?, val anonymousForGroupId: Int?) {
  /** A group may write in a thread only for writers it manages, or as its own anonymous writer. */
  fun canBeWrittenForBy(groupId: Int?): Boolean = groupId != null && (managedByGroupId == groupId || anonymousForGroupId == groupId)
  fun label(strings: Strings): String = if (anonymousForGroupId != null) strings.get(R.string.writer_anonymous) else name
}

/**
 * Who may edit or withdraw a queued letter in a thread: the writer themselves, or a group member only
 * when the group writes for that writer. A group that merely relays (or was shared) a letter reads it
 * and prints it; the words are not theirs to change.
 */
fun mayChangeLetters(viewerIsStaff: Boolean, viewerGroupId: Int?, writer: ThreadWriter?): Boolean =
  !viewerIsStaff || writer?.canBeWrittenForBy(viewerGroupId) == true

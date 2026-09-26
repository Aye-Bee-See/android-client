package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings
import java.time.Instant

/**
 * Invitation tokens (README, "Invitations"): 24 characters of Crockford base32, handed over by the inviting group in
 * person or over a channel both trust. Folded the way the server folds them before hashing (upper case, dashes and
 * spaces dropped, `O` read as `0`, `I` and `L` as `1`), so a token read aloud or pasted with its dashes still works.
 * The same length as a claim token, and twice an invite code's, which is how one box tells the three apart.
 */
object InvitationToken {
  const val LENGTH = 24
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  /** Nothing but dashes and spaces is dropped: a stray character stays, so that [problem] names it. */
  fun normalise(input: String): String = input.uppercase().filter { it != '-' && !it.isWhitespace() }
    .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

  fun isWellFormed(input: String): Boolean = normalise(input).let { t -> t.length == LENGTH && t.all { it in ALPHABET } }

  /** The first problem with what was typed, as a sentence, or null when it looks right. Worded as for claim tokens. */
  fun problem(input: String, strings: Strings): String? {
    val t = normalise(input)
    val bad = t.firstOrNull { it !in ALPHABET }
    return when {
      t.isEmpty() -> strings.get(R.string.token_empty)
      bad != null -> strings.get(R.string.token_bad_character, bad.toString())
      t.length < LENGTH -> strings.plural(R.plurals.token_too_short, t.length, LENGTH)
      t.length > LENGTH -> strings.plural(R.plurals.token_too_long, t.length, LENGTH)
      else -> null
    }
  }

  /** `XXXX-XXXX-…` for display. */
  fun pretty(input: String): String = normalise(input).chunked(4).joinToString("-")
}

/**
 * What an invitation token invites its holder to (`GET /invitation/invitation`). [Kind.MEMBER]: a group admin of
 * [groupName], able to act at once. [Kind.GROUP]: a new group, [inviteeName], vouched for by [groupName] (null when an
 * admin invited with nobody vouching), waiting for an admin unless [activatesAtOnce]. [groupFields] is what the form
 * may say about the new group; the server refuses anything else.
 */
data class GroupInvitation(
  val kind: Kind,
  val inviteeName: String,
  val groupName: String?,
  val expiresAt: Instant?,
  val activatesAtOnce: Boolean,
  val groupFields: Set<String> = emptySet(),
) {
  enum class Kind { MEMBER, GROUP }
}

/** The new group's profile, as the acceptance form sends it. Blank optional fields are left out. */
data class NewGroupProfile(
  val name: String,
  val city: String,
  val region: String = "",
  val country: String = "",
  val about: String = "",
  val website: String = "",
  val email: String = "",
  val services: Set<String> = emptySet(),
  /** One of [NetworkRoles.keys]; the services are [Services.keys]. */
  val networkRole: String = "collecting",
)

/** An invitation accepted and signed in: the group the account belongs to, and whether it may act yet. */
data class InvitationAccepted(val groupName: String, val activeNow: Boolean)

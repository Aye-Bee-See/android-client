package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import java.time.Instant

/**
 * Invite codes (API PR #116): 12 characters of Crockford base32, printed on a slip as `XXXX-XXXX-XXXX` and
 * typed by a newcomer. Entry is forgiving the way the server is: case, dashes and spaces do not matter, and
 * `O`, `I` and `L` read as `0`, `1` and `1`, so a slip read in poor light still works. The format is checked
 * here before anything is sent, because the public check is rate limited like claim checks.
 */
object InviteCode {
  const val LENGTH = 12
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  /** The link a slip's QR code carries, which the app opens on the join screen with the code filled in. */
  const val LINK_HOST = "letters.support"
  const val LINK_PATH = "/join"

  /** Upper case, letters and digits only, look-alikes folded as the server folds them. */
  fun normalise(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }
    .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

  fun isWellFormed(input: String): Boolean = normalise(input).let { c -> c.length == LENGTH && c.all { it in ALPHABET } }

  /** The first problem with what was typed, as a sentence, or null when it looks right. */
  fun problem(input: String, strings: Strings): String? {
    val c = normalise(input)
    val bad = c.firstOrNull { it !in ALPHABET }
    return when {
      c.isEmpty() -> strings.get(R.string.invite_code_empty)
      bad != null -> strings.get(R.string.invite_code_bad_character, bad.toString())
      c.length < LENGTH -> strings.plural(R.plurals.invite_code_too_short, c.length, LENGTH)
      c.length > LENGTH -> strings.plural(R.plurals.invite_code_too_long, c.length, LENGTH)
      else -> null
    }
  }

  /** `XXXX-XXXX-XXXX` for display and for the slip. */
  fun pretty(input: String): String = normalise(input).chunked(4).joinToString("-")

  /** What the QR code on a slip says: the public join link with the code, which a phone without the app can open too. */
  fun link(code: String): String = "https://$LINK_HOST$LINK_PATH?code=${pretty(code)}"
}

/** Who is vouching for an invite code: the group, and the date the code stops working. */
data class Invitation(val groupId: Int, val groupName: String, val expiresAt: Instant?)

/** One batch of invite codes as the group's list shows it: counts only, never the codes (API PR #116). */
data class InviteBatch(
  val id: String,
  val label: String?,
  val createdAt: Instant?,
  val expiresAt: Instant?,
  val total: Int,
  val used: Int,
  val unused: Int,
  val cancelled: Int,
  val expired: Int,
)

/** The group's standing with invite codes: how many unused ones it has out, of how many it may. */
data class InviteQuota(val outstanding: Int, val limit: Int, val batches: List<InviteBatch>)

/** A batch just issued: the only time the codes are ever said. With the group's name, for the slips. */
data class IssuedInvites(
  val batch: String,
  val label: String?,
  val expiresAt: Instant?,
  val codes: List<String>,
  val groupName: String,
  val outstanding: Int,
  val limit: Int,
)

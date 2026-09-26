package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings
import java.time.Instant

/**
 * Pen names (API PR #120): the name a writer's letters are signed with and a prisoner writes back to. Unique across
 * the site whatever the case or spacing; every name an account has used stays its own for ever. The shape is checked
 * here before the rate-limited public check: 3 to 40 characters, starting with a letter, of letters in any script,
 * digits, spaces, hyphens, apostrophes and dots. The server stores one space between words.
 */
object PenName {
  const val MIN = 3
  const val MAX = 40

  /** One space between words, as the server stores it. */
  fun normalise(input: String): String = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

  private fun allowed(c: Char) = c.isLetterOrDigit() || c == ' ' || c == '-' || c == '\'' || c == '\u2019' || c == '.'

  /** The first problem with the shape, as a sentence, or null when it looks right. */
  fun problem(input: String, strings: Strings): String? {
    val n = normalise(input)
    val bad = n.firstOrNull { !allowed(it) }
    return when {
      n.length < MIN -> strings.get(R.string.pen_name_too_short)
      n.length > MAX -> strings.get(R.string.pen_name_too_long)
      !n.first().isLetter() -> strings.get(R.string.pen_name_must_start_with_letter)
      bad != null -> strings.get(R.string.pen_name_bad_character, bad.toString())
      else -> null
    }
  }

  /** The name as the server compares names: one space between words, and case does not matter. */
  fun key(input: String): String = normalise(input).lowercase()

  /** Two parts, like a real name, read better in a mail room; the server says the same, and neither requires it. */
  fun hasTwoParts(input: String): Boolean = normalise(input).split(' ').count { it.isNotBlank() } >= 2
}

/** The server's answer to "is this name free?" */
data class PenNameCheck(val name: String, val available: Boolean, val reason: String?, val twoParts: Boolean)

/**
 * An account's names, the current one first, and what the limits (API #127) leave it today: one change of any kind
 * every [cooldownDays], and [newPerYear] brand-new names in a rolling year. The name chosen at sign-up is not a change.
 */
data class PenNames(
  val current: String?,
  val names: List<PenNameRow>,
  /** When the next change of any kind may happen. Null for an account that has never had a name: it may choose at once. */
  val changeAllowedAt: Instant? = null,
  /** Brand-new names left in the rolling year. Going back to one of the account's own old names does not spend one. */
  val newNamesLeft: Int = 2,
  /** When the oldest counted new name leaves the year, so another becomes possible. Null when none is counted. */
  val newNamesWindowEnds: Instant? = null,
  val cooldownDays: Int = 90,
  val newPerYear: Int = 2,
) {
  /** A cooldown of 0 days allows a change at any time, whatever a clock a few seconds behind the server's says. */
  fun canChange(now: Instant = Instant.now()): Boolean = cooldownDays == 0 || changeAllowedAt?.let { !it.isAfter(now) } ?: true

  /** An old name of this account's, which it may take back even with no new names left. */
  fun isOwnOldName(input: String): Boolean = PenName.key(input).let { k -> names.any { !it.current && PenName.key(it.name) == k } }

  /** The current name again, whatever the case or spacing: nothing to change. */
  fun isCurrent(input: String): Boolean = current?.let { PenName.key(it) == PenName.key(input) } ?: false
}
data class PenNameRow(val name: String, val current: Boolean, val since: Instant?)

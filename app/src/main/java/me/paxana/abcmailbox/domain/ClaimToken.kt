package me.paxana.abcmailbox.domain

import java.time.Instant

/**
 * Claim tokens: 24 characters from `0-9 A-Z` without `I L O U`, case-insensitive
 * on entry. People read them off a screen or a slip of paper, so entry is
 * forgiving (spaces, dashes, lower case) and display is grouped in fours.
 *
 * Checking the format locally matters: the API allows only 20 claim checks per
 * hour per address, so a typo should not cost a request.
 */
object ClaimToken {
  const val LENGTH = 24
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  /** Upper-cases and drops everything that is not a letter or digit. */
  fun normalise(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

  fun isWellFormed(input: String): Boolean = normalise(input).let { t -> t.length == LENGTH && t.all { it in ALPHABET } }

  /** The first problem with what was typed, as a sentence, or null when it looks right. */
  fun problem(input: String): String? {
    val t = normalise(input)
    val bad = t.firstOrNull { it !in ALPHABET }
    return when {
      t.isEmpty() -> "Enter the token your group gave you."
      bad != null -> "Tokens never contain the character $bad. Check for a look-alike (I, L, O, and U are not used)."
      t.length < LENGTH -> "That is ${t.length} characters; a token has $LENGTH."
      t.length > LENGTH -> "That is ${t.length} characters; a token has only $LENGTH."
      else -> null
    }
  }

  /** `ABCD-EFGH-…` for display. */
  fun pretty(input: String): String = normalise(input).chunked(4).joinToString("-")
}

data class ClaimInfo(val writerName: String, val groupName: String?, val expiresAt: Instant?)

package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings
import java.time.Instant

/**
 * The reply reference (API PR #120): nine digits, the last a check digit, printed as `4827-1935-6` at the foot of
 * every outgoing letter, copied by hand by the prisoner at the top of the reply, and typed by the volunteer who
 * opens the envelope. Digits only, because it is copied by people writing in many alphabets. The check digit is the
 * server's to verify (a wrong one is a 400 `checksum`, and nothing is looked up); the shape is checked here first.
 */
object ReplyReference {
  const val LENGTH = 9

  /** Digits only: dashes, spaces and anything else typed around them are dropped. */
  fun normalise(input: String): String = input.filter { it.isDigit() }

  fun isWellFormed(input: String): Boolean = normalise(input).length == LENGTH

  /** The first problem with what was typed, as a sentence, or null when it looks right. */
  fun problem(input: String, strings: Strings): String? {
    val stripped = input.filter { it != '-' && !it.isWhitespace() }
    val bad = stripped.firstOrNull { !it.isDigit() }
    val n = normalise(input)
    return when {
      n.isEmpty() -> strings.get(R.string.reference_empty)
      bad != null -> strings.get(R.string.reference_bad_character, bad.toString())
      n.length != LENGTH -> strings.plural(R.plurals.reference_wrong_length, n.length, LENGTH)
      else -> null
    }
  }

  /** `4827-1935-6`: four, four, and the check digit, as the footer prints it. */
  fun pretty(input: String): String = normalise(input).let { if (it.length == LENGTH) "${it.substring(0, 4)}-${it.substring(4, 8)}-${it.substring(8)}" else it }
}

/** What a reference looks up to: whose letter it was, to whom, and where the reply belongs. */
data class ReferenceLookup(
  val reference: String,
  val letter: ReferencedLetter?,
  val mailedAt: Instant?,
  val chatId: Int?,
  val writer: ReferencedWriter,
  val prisoner: ReferencedPrisoner?,
  val careOfName: String?,
)
data class ReferencedLetter(val id: Int, val chatId: Int?, val status: LetterStatus, val paper: Boolean, val createdAt: Instant?)
data class ReferencedWriter(val id: Int, val penName: String?, val name: String?, val anonymous: Boolean) {
  /** The pen name, else the name; the anonymous writer is worded by the screen. */
  val displayName: String? get() = penName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}
data class ReferencedPrisoner(val id: Int, val name: String)

/** A writer found by a current or former pen name. */
data class WriterMatch(val id: Int, val displayName: String, val anonymous: Boolean, val matchedName: String?, val matchedIsCurrent: Boolean)

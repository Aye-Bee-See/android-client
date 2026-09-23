package me.paxana.abcmailbox.domain

import androidx.annotation.StringRes
import me.paxana.abcmailbox.R

/**
 * The password rules are the clients' now (API PR #114): a split server never sees the password, so it can apply
 * none. The one hard rule is the length the network decided on. The meter is advice, not a gate: it is a rough
 * guess at how long a password would stand up to guessing, and it says so.
 */
object PasswordRules {
  const val MIN_LENGTH = 10

  enum class Strength(@StringRes val labelRes: Int, val bars: Int) { WEAK(R.string.strength_weak, 1), FAIR(R.string.strength_fair, 2), GOOD(R.string.strength_good, 3), STRONG(R.string.strength_strong, 4) }

  /**
   * Bits of guessing work, estimated the way most meters do: the size of the alphabet the characters come from,
   * raised to the length, with penalties for the shortcuts people take (repeats, runs, one kind of character).
   * Four or more words with spaces is treated as a passphrase and scored by words, which is what such passwords
   * are made of. None of this can know a password is on a leaked list; nothing offline can.
   */
  fun strength(password: String): Strength {
    if (password.length < MIN_LENGTH) return Strength.WEAK
    val words = password.trim().split(Regex("\\s+")).filter { it.length >= 3 }
    // A word from a large everyday vocabulary is worth about 14 bits (an 8,000-word list is 13; people pick from more).
    val bits = if (words.size >= 4) words.size * 14.0 + (password.length - words.sumOf { it.length }).coerceAtLeast(0) * 0.5 else {
      var alphabet = 0
      if (password.any { it.isLowerCase() }) alphabet += 26
      if (password.any { it.isUpperCase() }) alphabet += 26
      if (password.any { it.isDigit() }) alphabet += 10
      if (password.any { !it.isLetterOrDigit() }) alphabet += 20
      if (password.any { it.code > 127 }) alphabet += 40
      var effective = password.length.toDouble()
      // Repeated characters and runs ("aaaa", "1234", "abcd") add nothing worth counting.
      for (i in 1 until password.length) {
        val d = password[i].code - password[i - 1].code
        if (d == 0 || d == 1 || d == -1) effective -= 0.7
      }
      effective * Math.log(alphabet.coerceAtLeast(2).toDouble()) / Math.log(2.0)
    }
    return when {
      bits < 40 -> Strength.WEAK
      bits < 55 -> Strength.FAIR
      bits < 70 -> Strength.GOOD
      else -> Strength.STRONG
    }
  }
}

package me.paxana.abcmailbox.crypto

import java.security.MessageDigest

/**
 * Claim tokens and recovery codes: secrets a person reads off a screen or a
 * slip of paper and types back. 24 characters from `0-9 A-Z` without
 * `I L O U` (about 120 bits).
 *
 * Both clients MUST normalise a typed code the same way before using it,
 * because the normalised text is what goes into the key derivation: upper
 * case, letters and digits only. "abcd-efgh" and "ABCDEFGH" are one code.
 */
object SecretCodes {
  const val LENGTH = 24
  const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  fun generate(): String {
    // 32 symbols, so a byte masked to 5 bits maps uniformly onto the alphabet.
    val bytes = Sodium.randomBytes(LENGTH)
    return buildString(LENGTH) { bytes.forEach { append(ALPHABET[it.toInt() and 31]) } }
  }

  fun normalise(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

  fun isWellFormed(input: String): Boolean = normalise(input).let { t -> t.length == LENGTH && t.all { it in ALPHABET } }

  fun pretty(input: String): String = normalise(input).chunked(4).joinToString("-")

  /** What the server stores for a claim token: SHA-256 (hex) of the normalised text. */
  fun hashHex(code: String): String =
    MessageDigest.getInstance("SHA-256").digest(normalise(code).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

package me.paxana.abcmailbox.crypto

import kotlinx.serialization.Serializable

/**
 * The `kdfParams` object stored beside every wrapped private key. Agreed
 * between the web and Android clients on 12 September 2026 (API README,
 * "End-to-end mode"); the server only checks that it is an object with a
 * string `kdf`. Both clients must be able to read what the other wrote, or
 * an account created on one cannot unlock on the other.
 *
 * `alg` is libsodium's algorithm id for `crypto_pwhash` (Argon2id 1.3 = 2),
 * recorded so a future library default cannot silently change how an old
 * key is derived. Costs are stored per account so they can be raised for new
 * accounts without invalidating existing ones.
 */
@Serializable
data class KdfParams(
  val kdf: String = "argon2id",
  val alg: Int = Sodium.ALG_ARGON2ID13,
  val opslimit: Long = Sodium.OPSLIMIT_INTERACTIVE,
  val memlimit: Int = Sodium.MEMLIMIT_INTERACTIVE,
) {
  init {
    require(kdf == "argon2id") { "unsupported kdf: $kdf" }
    require(alg == Sodium.ALG_ARGON2ID13) { "unsupported argon2 variant: $alg" }
    require(opslimit >= 1) { "opslimit must be positive" }
    require(memlimit >= 8_192) { "memlimit must be at least 8 KiB" }
  }

  fun derive(secret: String, salt: ByteArray): ByteArray =
    Sodium.deriveKey(secret, salt, opslimit = opslimit, memlimit = memlimit, algorithm = alg)
}

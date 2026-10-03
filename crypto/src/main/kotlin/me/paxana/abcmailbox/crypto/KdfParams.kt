package me.paxana.abcmailbox.crypto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 *
 * kotlinx.serialization drops properties that equal their defaults unless
 * told otherwise; `@EncodeDefault` makes every field always appear on the
 * wire, whatever `Json` configuration the caller uses.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KdfParams(
  @EncodeDefault val kdf: String = "argon2id",
  @EncodeDefault val alg: Int = Sodium.ALG_ARGON2ID13,
  @EncodeDefault val opslimit: Long = Sodium.OPSLIMIT_INTERACTIVE,
  @EncodeDefault val memlimit: Int = Sodium.MEMLIMIT_INTERACTIVE,
) {
  init {
    require(kdf == "argon2id") { "unsupported kdf: $kdf" }
    require(alg == Sodium.ALG_ARGON2ID13) { "unsupported argon2 variant: $alg" }
    require(opslimit >= 1) { "opslimit must be positive" }
    require(memlimit >= 8_192) { "memlimit must be at least 8 KiB" }
    // The costs come from the server, with the wrapped key or the sign-in handshake. Without a ceiling, a wrong or
    // hostile value has the phone hash for hours or ask for memory it does not have. libsodium's own "sensitive"
    // tier (4 passes, 1 GiB) is the most any client of this API has reason to ask of a phone.
    require(opslimit <= MAX_OPSLIMIT) { "opslimit is beyond what a phone should be asked for: $opslimit" }
    require(memlimit <= MAX_MEMLIMIT) { "memlimit is beyond what a phone should be asked for: $memlimit" }
  }

  /**
   * No weaker than what every client makes a new account with. Asked of the sign-in handshake only, where the server
   * names the cost and gets the result: a server that named a trivial cost would be handed an auth key cheap to turn
   * back into the password. A key wrapped long ago under a lower cost is still opened; nothing leaves the phone there.
   */
  val meetsFloor: Boolean get() = opslimit >= Sodium.OPSLIMIT_INTERACTIVE && memlimit >= Sodium.MEMLIMIT_INTERACTIVE

  companion object {
    const val MAX_OPSLIMIT = 10L
    const val MAX_MEMLIMIT = 1_073_741_824
  }

  fun derive(secret: String, salt: ByteArray): ByteArray =
    Sodium.deriveKey(secret, salt, opslimit = opslimit, memlimit = memlimit, algorithm = alg)
}

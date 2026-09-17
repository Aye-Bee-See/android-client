package me.paxana.abcmailbox.crypto

/** A private key sealed to someone else's public key, as the API stores it (base64). */
class SealedKeyPair(val keyPair: Sodium.KeyPair, val publicKey: String, val sealedPrivateKey: String)

/** Everything the API needs for a claim token made on the device. The token itself never leaves it. */
class NewClaimToken(val token: String, val tokenHash: String, val wrapped: WrappedKey)

class KeyMismatchException : Exception("The opened private key does not belong to the published public key.")

/**
 * The group's side of end-to-end encryption. A group has one keypair; each
 * member holds the group's private key sealed to their own public key, so
 * members come and go without a shared password. A writer the group manages
 * has a keypair the group made, with the private key sealed to the group
 * (custody) until the writer claims the account with a token.
 *
 * All of it is `crypto_box_seal` over a raw 32-byte private key, base64.
 */
object GroupKeys {

  /** A new keypair with its private key sealed to [holderPublicKey]: a new group for its first member, or a new writer for the group. */
  fun createSealedTo(holderPublicKey: String): SealedKeyPair {
    val kp = Sodium.keypair()
    return SealedKeyPair(kp, Sodium.toBase64(kp.publicKey), sealPrivateKey(kp.privateKey, holderPublicKey))
  }

  /** Hand an opened private key to one more holder (a new member). */
  fun sealPrivateKey(privateKey: ByteArray, holderPublicKey: String): String =
    Sodium.toBase64(Sodium.seal(privateKey, Sodium.fromBase64(holderPublicKey)))

  /**
   * Open a private key sealed to [holder]. The public key is recomputed from
   * what came out and compared with [expectedPublicKey], the one the server
   * publishes, so a swapped or stale blob is refused rather than used.
   */
  fun open(sealedPrivateKey: String, holder: Sodium.KeyPair, expectedPublicKey: String?): Sodium.KeyPair {
    val privateKey = try {
      Sodium.sealOpen(Sodium.fromBase64(sealedPrivateKey), holder.publicKey, holder.privateKey)
    } catch (e: Exception) {
      throw CannotOpenException("This key was not sealed to the key trying to open it.")
    }
    val publicKey = Sodium.publicKeyOf(privateKey)
    if (expectedPublicKey != null && !publicKey.contentEquals(Sodium.fromBase64(expectedPublicKey))) throw KeyMismatchException()
    return Sodium.KeyPair(publicKey, privateKey)
  }

  /** A fresh claim token with the writer's private key wrapped under it (Argon2id, so this is slow on purpose). */
  fun claimToken(writerPrivateKey: ByteArray): NewClaimToken {
    val token = SecretCodes.generate()
    return NewClaimToken(token, SecretCodes.hashHex(token), KeyWrapping.wrap(writerPrivateKey, SecretCodes.normalise(token)))
  }
}

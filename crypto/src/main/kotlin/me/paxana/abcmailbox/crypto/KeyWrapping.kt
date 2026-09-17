package me.paxana.abcmailbox.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A private key wrapped under a human secret (password, recovery code, or
 * claim token): Argon2id turns the secret and a random salt into a 32-byte
 * key, and XChaCha20-Poly1305 encrypts the private key with it.
 *
 * Wire format, fixed by the API's reference client: `wrapped` is the JSON
 * string `{"ciphertext": b64, "nonce": b64}`, `salt` is base64 of 16 bytes,
 * and `params` is the agreed [KdfParams] object.
 */
data class WrappedKey(val wrapped: String, val salt: String, val params: KdfParams)

class WrongSecretException : Exception("The secret does not open this key.")

object KeyWrapping {
  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Box(val ciphertext: String, val nonce: String)

  fun wrap(privateKey: ByteArray, secret: String, params: KdfParams = KdfParams()): WrappedKey {
    val salt = Sodium.randomBytes(Sodium.SALT_BYTES)
    val key = params.derive(secret, salt)
    val enc = Sodium.aeadEncrypt(privateKey, key)
    key.fill(0)
    val wrapped = json.encodeToString(Box.serializer(), Box(Sodium.toBase64(enc.ciphertext), Sodium.toBase64(enc.nonce)))
    return WrappedKey(wrapped, Sodium.toBase64(salt), params)
  }

  /** @throws WrongSecretException when the secret is wrong (or the data was tampered with). */
  fun unwrap(wrapped: String, secret: String, salt: String, params: KdfParams): ByteArray {
    val box = json.decodeFromString(Box.serializer(), wrapped)
    val key = params.derive(secret, Sodium.fromBase64(salt))
    return try {
      Sodium.aeadDecrypt(Sodium.fromBase64(box.ciphertext), Sodium.fromBase64(box.nonce), key)
    } catch (e: Exception) {
      throw WrongSecretException()
    } finally {
      key.fill(0)
    }
  }
}

/** The seven fields the API stores for an account's keys (register, `PUT /auth/keys`, claim). */
data class AccountKeyFields(
  val publicKey: String,
  val password: WrappedKey,
  val recovery: WrappedKey,
)

object AccountKeys {
  /** A brand-new keypair wrapped under the password and under a recovery code. */
  fun create(password: String, recoveryCode: String): Pair<Sodium.KeyPair, AccountKeyFields> {
    val kp = Sodium.keypair()
    return kp to wrapExisting(kp, password, recoveryCode)
  }

  /** Same keypair, new secrets: used by claim (token to password) and by recovery-code rotation. */
  fun wrapExisting(keyPair: Sodium.KeyPair, password: String, recoveryCode: String) = AccountKeyFields(
    publicKey = Sodium.toBase64(keyPair.publicKey),
    password = KeyWrapping.wrap(keyPair.privateKey, password),
    recovery = KeyWrapping.wrap(keyPair.privateKey, SecretCodes.normalise(recoveryCode)),
  )

  fun unlockWithPassword(publicKey: String, wrapped: String, password: String, salt: String, params: KdfParams) =
    Sodium.KeyPair(Sodium.fromBase64(publicKey), KeyWrapping.unwrap(wrapped, password, salt, params))

  /** Claim tokens and recovery codes are typed by people, so they are normalised before derivation. */
  fun unlockWithCode(publicKey: String, wrapped: String, code: String, salt: String, params: KdfParams) =
    Sodium.KeyPair(Sodium.fromBase64(publicKey), KeyWrapping.unwrap(wrapped, SecretCodes.normalise(code), salt, params))

  /** Recovery step two: prove possession of the private key by opening the server's sealed challenge. */
  fun openChallenge(sealedChallenge: String, keyPair: Sodium.KeyPair): String =
    Sodium.toBase64(Sodium.sealOpen(Sodium.fromBase64(sealedChallenge), keyPair.publicKey, keyPair.privateKey))
}

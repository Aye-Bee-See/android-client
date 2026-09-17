package me.paxana.abcmailbox.data.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import me.paxana.abcmailbox.crypto.AccountKeyFields
import me.paxana.abcmailbox.crypto.EncryptedLetter
import me.paxana.abcmailbox.crypto.EncryptedText
import me.paxana.abcmailbox.crypto.Envelope
import me.paxana.abcmailbox.crypto.KdfParams
import me.paxana.abcmailbox.crypto.Reader
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.crypto.WrappedKey

class FixedMode(initial: EncryptionMode) : EncryptionModeRepository {
  override val mode = MutableStateFlow(initial)
  override suspend fun refresh() = mode.value
}

class InMemoryVault : KeyVault {
  private var held: Pair<Int, Sodium.KeyPair>? = null
  override val unlockedFor = MutableStateFlow<Int?>(null)
  override fun keyPair(userId: Int) = held?.takeIf { it.first == userId }?.second
  override suspend fun store(userId: Int, keyPair: Sodium.KeyPair) { held = userId to keyPair; unlockedFor.value = userId }
  override suspend fun clear() { held = null; unlockedFor.value = null }
}

/**
 * A see-through stand-in for libsodium (which cannot load in `:app`'s JVM tests).
 * "Wrapping" and "sealing" are readable strings, so a test can assert who a key
 * was sealed to and which secret wrapped it. The real cryptography is proven in
 * `:crypto` against libsodium.js.
 */
class FakeCryptoEngine : CryptoEngine {
  fun keyPairFor(publicKey: String) = Sodium.KeyPair(publicKey.toByteArray(), "private-of-$publicKey".toByteArray())
  private fun pub(kp: Sodium.KeyPair) = String(kp.publicKey)
  private fun wrap(kp: Sodium.KeyPair, secret: String) = WrappedKey("wrapped(${pub(kp)})under($secret)", "salt", KdfParams())
  private fun fields(kp: Sodium.KeyPair, password: String, code: String) = AccountKeyFields(pub(kp), wrap(kp, password), wrap(kp, code))

  override suspend fun createAccountKeys(password: String) = keyPairFor("PUB-NEW").let { NewAccountKeys(it, fields(it, password, "NEWCODE"), "NEWCODE") }
  override suspend fun rewrapAll(keyPair: Sodium.KeyPair, password: String) = NewAccountKeys(keyPair, fields(keyPair, password, "REWRAPCODE"), "REWRAPCODE")
  override suspend fun wrapForPassword(keyPair: Sodium.KeyPair, password: String) = wrap(keyPair, password)

  private fun unwrap(publicKey: String, wrapped: String, secret: String): Sodium.KeyPair {
    if (wrapped != "wrapped($publicKey)under($secret)") throw IllegalStateException("wrong secret")
    return keyPairFor(publicKey)
  }
  override suspend fun unlockWithPassword(publicKey: String, wrapped: String, password: String, salt: String, params: JsonElement) = unwrap(publicKey, wrapped, password)
  override suspend fun unlockWithCode(publicKey: String, wrapped: String, code: String, salt: String, params: JsonElement) = unwrap(publicKey, wrapped, code)
  override fun openChallenge(sealedChallenge: String, keyPair: Sodium.KeyPair) = "opened($sealedChallenge)by(${pub(keyPair)})"

  override fun encryptLetter(body: String, relayNote: String?, readers: List<Reader>): EncryptedLetter {
    val key = "KEY".toByteArray()
    return EncryptedLetter(encryptText(body, key), relayNote?.let { encryptText(it, key) }, readers.map { Envelope(it.type, it.id, "sealed(KEY)to(${it.publicKey})", it.keyVersion) }, key)
  }
  /** Envelopes are sealed to a base64 public key (that is how keys travel), so opening compares the same form. */
  fun sealedTo(keyPair: Sodium.KeyPair) = "sealed(KEY)to(${java.util.Base64.getEncoder().encodeToString(keyPair.publicKey)})"

  override fun openEnvelope(wrappedKey: String, keyPair: Sodium.KeyPair): ByteArray {
    if (wrappedKey != sealedTo(keyPair)) throw IllegalStateException("not sealed to this key")
    return "KEY".toByteArray()
  }
  override fun encryptText(text: String, contentKey: ByteArray) = EncryptedText("enc[$text]", "nonce")
  override fun decryptText(ciphertext: String, nonce: String, contentKey: ByteArray) = ciphertext.removePrefix("enc[").removeSuffix("]")
  override fun encryptFile(bytes: ByteArray, contentKey: ByteArray) = bytes.reversedArray() to "file-nonce"
  override fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray) = ciphertext.reversedArray()
}

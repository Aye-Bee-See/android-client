package me.paxana.abcmailbox.data.crypto

import me.paxana.abcmailbox.data.crypto.SplitKeys
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

  // The split scheme, see-through: the "auth key" names the password and salt it came from, and the "wrap key" is a
  // byte string the fake can recognise. Tests read what was sent and check it is never the password itself.
  override suspend fun deriveSplit(password: String, salt: String, params: JsonElement) = SplitKeys("wrap($password)with($salt)".toByteArray(), "auth($password)with($salt)")
  var salts = 0
  override fun newSalt() = "salt-${++salts}"
  override fun defaultParams() = KdfParams()
  private fun splitWrap(kp: Sodium.KeyPair, password: String, salt: String) = WrappedKey("wrapped(${pub(kp)})underwrap($password)with($salt)", salt, KdfParams())
  override suspend fun createAccountKeysSplit(password: String) = keyPairFor("PUB-NEW").let { kp -> val salt = newSalt(); NewAccountKeys(kp, AccountKeyFields(pub(kp), splitWrap(kp, password, salt), wrap(kp, "NEWCODE")), "NEWCODE", "auth($password)with($salt)") }
  override suspend fun createAccountKeysUnderWrapKey(wrapKey: ByteArray, salt: String, params: JsonElement) = keyPairFor("PUB-NEW").let { kp -> NewAccountKeys(kp, AccountKeyFields(pub(kp), WrappedKey("wrapped(${pub(kp)})under${String(wrapKey)}", salt, KdfParams()), wrap(kp, "NEWCODE")), "NEWCODE") }
  override suspend fun rewrapAllSplit(keyPair: Sodium.KeyPair, password: String) = newSalt().let { salt -> NewAccountKeys(keyPair, AccountKeyFields(pub(keyPair), splitWrap(keyPair, password, salt), wrap(keyPair, "REWRAPCODE")), "REWRAPCODE", "auth($password)with($salt)") }
  override suspend fun wrapForSplitPassword(keyPair: Sodium.KeyPair, password: String) = newSalt().let { salt -> splitWrap(keyPair, password, salt) to "auth($password)with($salt)" }
  override fun unlockWithWrapKey(publicKey: String, wrapped: String, wrapKey: ByteArray): Sodium.KeyPair {
    if (wrapped != "wrapped($publicKey)under${String(wrapKey)}") throw IllegalStateException("wrong wrap key")
    return keyPairFor(publicKey)
  }

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

  // Group side. Public keys travel as base64, so the fake's readable names are base64 of e.g. "PUB-GROUP".
  private val b64 = java.util.Base64.getEncoder()
  fun publicText(kp: Sodium.KeyPair): String = b64.encodeToString(kp.publicKey)
  fun keyPairForBase64(publicKey: String) = keyPairFor(String(java.util.Base64.getDecoder().decode(publicKey)))
  var nextNewKey = "PUB-MADE"
  var nextToken = "T0KENT0KENT0KENT0KENT0KE"

  override fun newKeyPairSealedTo(holderPublicKey: String): me.paxana.abcmailbox.crypto.SealedKeyPair {
    val kp = keyPairFor(nextNewKey)
    return me.paxana.abcmailbox.crypto.SealedKeyPair(kp, publicText(kp), sealPrivateKey(kp.privateKey, holderPublicKey))
  }
  override fun sealPrivateKey(privateKey: ByteArray, holderPublicKey: String) = "sealedkey(${String(privateKey)})to($holderPublicKey)"
  override fun openSealedKey(sealedPrivateKey: String, holder: Sodium.KeyPair, expectedPublicKey: String?): Sodium.KeyPair {
    val m = Regex("""sealedkey\(private-of-(.+)\)to\((.+)\)""").matchEntire(sealedPrivateKey) ?: throw IllegalStateException("not a sealed key")
    if (m.groupValues[2] != publicText(holder)) throw IllegalStateException("not sealed to this holder")
    val kp = keyPairFor(m.groupValues[1])
    if (expectedPublicKey != null && expectedPublicKey != publicText(kp)) throw me.paxana.abcmailbox.crypto.KeyMismatchException()
    return kp
  }
  override suspend fun newClaimToken(writerPrivateKey: ByteArray) = me.paxana.abcmailbox.crypto.NewClaimToken(
    nextToken, "hash($nextToken)", WrappedKey("wrapped(${String(writerPrivateKey)})under($nextToken)", "claim-salt", KdfParams()))
  override fun sealContentKey(contentKey: ByteArray, reader: Reader) = Envelope(reader.type, reader.id, "sealed(${String(contentKey)})to(${reader.publicKey})", reader.keyVersion)
}

/** A keyring whose contents a test sets directly. `loads` counts how often it was asked, `forced` how often it was told to look again. */
class FakeKeyring(initial: GroupKeyState = GroupKeyState.NotNeeded) : GroupKeyring {
  override val state = MutableStateFlow(initial)
  val custody = mutableMapOf<Int, Sodium.KeyPair>()
  var loads = 0
  var forced = 0
  override suspend fun load(force: Boolean): GroupKeyState { loads++; if (force) forced++; return state.value }
  override fun groupKey(): GroupKey? = (state.value as? GroupKeyState.Ready)?.key
  override fun writerKey(writerId: Int): Sodium.KeyPair? = custody[writerId]
  override fun remember(writerId: Int, keyPair: Sodium.KeyPair) { custody[writerId] = keyPair }
  override fun forget() { custody.clear(); state.value = GroupKeyState.NotNeeded }
}

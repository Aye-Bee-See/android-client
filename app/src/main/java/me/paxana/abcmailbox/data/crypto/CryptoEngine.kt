package me.paxana.abcmailbox.data.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import me.paxana.abcmailbox.crypto.AccountKeyFields
import me.paxana.abcmailbox.crypto.AccountKeys
import me.paxana.abcmailbox.crypto.EncryptedLetter
import me.paxana.abcmailbox.crypto.EncryptedText
import me.paxana.abcmailbox.crypto.Envelope
import me.paxana.abcmailbox.crypto.GroupKeys
import me.paxana.abcmailbox.crypto.NewClaimToken
import me.paxana.abcmailbox.crypto.SealedKeyPair
import me.paxana.abcmailbox.crypto.KdfParams
import me.paxana.abcmailbox.crypto.KeyWrapping
import me.paxana.abcmailbox.crypto.LetterCipher
import me.paxana.abcmailbox.crypto.Reader
import me.paxana.abcmailbox.crypto.SecretCodes
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.crypto.WrappedKey
import javax.inject.Inject
import javax.inject.Singleton

class NewAccountKeys(val keyPair: Sodium.KeyPair, val fields: AccountKeyFields, val recoveryCode: String)

/**
 * The app's doorway to the `:crypto` module. An interface so JVM unit tests in
 * `:app` can fake it (the Android build of libsodium cannot load on a desktop
 * JVM; the real thing is tested in `:crypto` and on the emulator). Everything
 * that runs Argon2id is `suspend` and moves off the main thread, because it is
 * deliberately slow (64 MiB, about half a second on a phone).
 */
interface CryptoEngine {
  suspend fun createAccountKeys(password: String): NewAccountKeys
  suspend fun rewrapAll(keyPair: Sodium.KeyPair, password: String): NewAccountKeys
  suspend fun wrapForPassword(keyPair: Sodium.KeyPair, password: String): WrappedKey
  suspend fun unlockWithPassword(publicKey: String, wrapped: String, password: String, salt: String, params: JsonElement): Sodium.KeyPair
  suspend fun unlockWithCode(publicKey: String, wrapped: String, code: String, salt: String, params: JsonElement): Sodium.KeyPair
  fun openChallenge(sealedChallenge: String, keyPair: Sodium.KeyPair): String
  fun encryptLetter(body: String, relayNote: String?, readers: List<Reader>): EncryptedLetter
  fun openEnvelope(wrappedKey: String, keyPair: Sodium.KeyPair): ByteArray
  fun encryptText(text: String, contentKey: ByteArray): EncryptedText
  fun decryptText(ciphertext: String, nonce: String, contentKey: ByteArray): String
  fun encryptFile(bytes: ByteArray, contentKey: ByteArray): Pair<ByteArray, String>
  fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray): ByteArray

  // The group's side: see GroupKeys in :crypto.
  /** A new keypair whose private half is sealed to [holderPublicKey]: a group for its first member, or a writer for the group. */
  fun newKeyPairSealedTo(holderPublicKey: String): SealedKeyPair
  /** Opens a private key sealed to [holder] and checks it against the public key the server publishes. */
  fun openSealedKey(sealedPrivateKey: String, holder: Sodium.KeyPair, expectedPublicKey: String?): Sodium.KeyPair
  fun sealPrivateKey(privateKey: ByteArray, holderPublicKey: String): String
  /** Runs Argon2id, so it is slow and off the main thread. */
  suspend fun newClaimToken(writerPrivateKey: ByteArray): NewClaimToken
  /** One more envelope for a letter whose content key is already open (forwarding). */
  fun sealContentKey(contentKey: ByteArray, reader: Reader): Envelope
}

@Singleton
class SodiumCryptoEngine @Inject constructor() : CryptoEngine {
  private val json = Json { ignoreUnknownKeys = true }
  private fun params(element: JsonElement): KdfParams = json.decodeFromJsonElement(element)

  override suspend fun createAccountKeys(password: String) = withContext(Dispatchers.Default) {
    Sodium.initialize()
    val code = SecretCodes.generate()
    val (kp, fields) = AccountKeys.create(password, code)
    NewAccountKeys(kp, fields, code)
  }

  override suspend fun rewrapAll(keyPair: Sodium.KeyPair, password: String) = withContext(Dispatchers.Default) {
    Sodium.initialize()
    val code = SecretCodes.generate()
    NewAccountKeys(keyPair, AccountKeys.wrapExisting(keyPair, password, code), code)
  }

  override suspend fun wrapForPassword(keyPair: Sodium.KeyPair, password: String) = withContext(Dispatchers.Default) {
    Sodium.initialize(); KeyWrapping.wrap(keyPair.privateKey, password)
  }

  override suspend fun unlockWithPassword(publicKey: String, wrapped: String, password: String, salt: String, params: JsonElement) =
    withContext(Dispatchers.Default) { Sodium.initialize(); AccountKeys.unlockWithPassword(publicKey, wrapped, password, salt, params(params)) }

  override suspend fun unlockWithCode(publicKey: String, wrapped: String, code: String, salt: String, params: JsonElement) =
    withContext(Dispatchers.Default) { Sodium.initialize(); AccountKeys.unlockWithCode(publicKey, wrapped, code, salt, params(params)) }

  override fun openChallenge(sealedChallenge: String, keyPair: Sodium.KeyPair): String { Sodium.initialize(); return AccountKeys.openChallenge(sealedChallenge, keyPair) }
  override fun encryptLetter(body: String, relayNote: String?, readers: List<Reader>): EncryptedLetter { Sodium.initialize(); return LetterCipher.encrypt(body, relayNote, readers) }
  override fun openEnvelope(wrappedKey: String, keyPair: Sodium.KeyPair): ByteArray { Sodium.initialize(); return LetterCipher.openEnvelope(wrappedKey, keyPair) }
  override fun encryptText(text: String, contentKey: ByteArray) = LetterCipher.encryptText(text, contentKey)
  override fun decryptText(ciphertext: String, nonce: String, contentKey: ByteArray) = LetterCipher.decryptText(ciphertext, nonce, contentKey)
  override fun encryptFile(bytes: ByteArray, contentKey: ByteArray) = LetterCipher.encryptFile(bytes, contentKey)
  override fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray) = LetterCipher.decryptFile(ciphertext, nonce, contentKey)

  override fun newKeyPairSealedTo(holderPublicKey: String): SealedKeyPair { Sodium.initialize(); return GroupKeys.createSealedTo(holderPublicKey) }
  override fun openSealedKey(sealedPrivateKey: String, holder: Sodium.KeyPair, expectedPublicKey: String?): Sodium.KeyPair { Sodium.initialize(); return GroupKeys.open(sealedPrivateKey, holder, expectedPublicKey) }
  override fun sealPrivateKey(privateKey: ByteArray, holderPublicKey: String): String { Sodium.initialize(); return GroupKeys.sealPrivateKey(privateKey, holderPublicKey) }
  override suspend fun newClaimToken(writerPrivateKey: ByteArray) = withContext(Dispatchers.Default) { Sodium.initialize(); GroupKeys.claimToken(writerPrivateKey) }
  override fun sealContentKey(contentKey: ByteArray, reader: Reader): Envelope { Sodium.initialize(); return LetterCipher.seal(contentKey, reader) }
}

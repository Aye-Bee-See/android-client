package me.paxana.abcmailbox.crypto

/** Someone a letter's content key is sealed to. `keyVersion` is required for groups (their keys rotate). */
data class Reader(val type: String, val id: Int, val publicKey: String, val keyVersion: Int? = null) {
  companion object {
    const val USER = "user"
    const val CHAPTER = "chapter"
  }
}

data class Envelope(val readerType: String, val readerId: Int, val wrappedKey: String, val keyVersion: Int? = null)

data class EncryptedText(val ciphertext: String, val nonce: String)

class EncryptedLetter(
  val body: EncryptedText,
  val relayNote: EncryptedText?,
  val envelopes: List<Envelope>,
  /** Kept by the caller only long enough to encrypt attachments under the same key. */
  val contentKey: ByteArray,
)

class CannotOpenException(message: String) : Exception(message)

/**
 * One random content key per letter; the body, the relay note, and every
 * attachment are encrypted with it (fresh nonce each), and the key itself is
 * sealed once per reader. Adding a reader later never touches the letter.
 */
object LetterCipher {

  fun encrypt(body: String, relayNote: String?, readers: List<Reader>): EncryptedLetter {
    require(readers.isNotEmpty()) { "a letter needs at least one reader" }
    val contentKey = Sodium.randomBytes(Sodium.KEY_BYTES)
    return EncryptedLetter(
      body = encryptText(body, contentKey),
      relayNote = relayNote?.takeIf { it.isNotBlank() }?.let { encryptText(it, contentKey) },
      envelopes = readers.map { seal(contentKey, it) },
      contentKey = contentKey,
    )
  }

  fun seal(contentKey: ByteArray, reader: Reader) = Envelope(
    readerType = reader.type,
    readerId = reader.id,
    wrappedKey = Sodium.toBase64(Sodium.seal(contentKey, Sodium.fromBase64(reader.publicKey))),
    keyVersion = reader.keyVersion,
  )

  /** @throws CannotOpenException when the envelope was not sealed to this keypair. */
  fun openEnvelope(wrappedKey: String, keyPair: Sodium.KeyPair): ByteArray = try {
    Sodium.sealOpen(Sodium.fromBase64(wrappedKey), keyPair.publicKey, keyPair.privateKey)
  } catch (e: Exception) {
    throw CannotOpenException("This letter was not sealed to your key.")
  }

  fun encryptText(text: String, contentKey: ByteArray): EncryptedText =
    Sodium.aeadEncrypt(text.toByteArray(Charsets.UTF_8), contentKey).let { EncryptedText(Sodium.toBase64(it.ciphertext), Sodium.toBase64(it.nonce)) }

  fun decryptText(ciphertext: String, nonce: String, contentKey: ByteArray): String = try {
    String(Sodium.aeadDecrypt(Sodium.fromBase64(ciphertext), Sodium.fromBase64(nonce), contentKey), Charsets.UTF_8)
  } catch (e: Exception) {
    throw CannotOpenException("This letter could not be decrypted.")
  }

  /** Attachment bytes under the letter's content key; the nonce travels as a form field. */
  fun encryptFile(bytes: ByteArray, contentKey: ByteArray): Pair<ByteArray, String> =
    Sodium.aeadEncrypt(bytes, contentKey).let { it.ciphertext to Sodium.toBase64(it.nonce) }

  fun decryptFile(ciphertext: ByteArray, nonce: String, contentKey: ByteArray): ByteArray = try {
    Sodium.aeadDecrypt(ciphertext, Sodium.fromBase64(nonce), contentKey)
  } catch (e: Exception) {
    throw CannotOpenException("This file could not be decrypted.")
  }
}

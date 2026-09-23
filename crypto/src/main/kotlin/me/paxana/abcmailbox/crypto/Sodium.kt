package me.paxana.abcmailbox.crypto

import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_KEYBYTES
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_MEMLIMIT_INTERACTIVE
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_OPSLIMIT_INTERACTIVE
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_SALTBYTES
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.Base64Variants
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import java.text.Normalizer

/**
 * The only file that talks to the libsodium binding. Everything else in this
 * module (and the app) goes through these functions, so swapping the binding
 * later touches one place. The names mirror the API's `services/crypto.js`,
 * which is the contract every client must match:
 *
 * - keypairs: X25519 (`crypto_box_keypair`)
 * - sealing a key to a public key: `crypto_box_seal`
 * - bodies, notes, files, and wrapped private keys: `crypto_aead_xchacha20poly1305_ietf`
 *   with no associated data and a random 24-byte nonce
 * - password, recovery code, and claim token derivation: Argon2id (`crypto_pwhash`)
 * - base64: the standard alphabet with padding
 *
 * The binding works in `UByteArray`; the rest of the code uses `ByteArray`,
 * so conversions happen here and nowhere else.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object Sodium {

  const val KEY_BYTES = 32
  const val NONCE_BYTES = 24
  const val KDF_CONTEXT_BYTES = 8
  const val SALT_BYTES = 16

  /** Default Argon2id cost: libsodium's "interactive" tier, fast enough for a phone. */
  const val OPSLIMIT_INTERACTIVE: Long = 2
  const val MEMLIMIT_INTERACTIVE: Int = 67_108_864
  const val ALG_ARGON2ID13: Int = 2

  /** Loads the native library. Safe to call more than once; cheap after the first. */
  fun initialize() {
    // JNA marshals Java strings to C strings with this encoding; pin it so a password
    // is the same bytes on every JVM and on Android.
    System.setProperty("jna.encoding", "UTF-8")
    if (!LibsodiumInitializer.isInitialized()) {
      LibsodiumInitializer.initializeWithCallback { }
    }
    check(KEY_BYTES == crypto_aead_xchacha20poly1305_ietf_KEYBYTES)
    check(NONCE_BYTES == crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
    check(SALT_BYTES == crypto_pwhash_SALTBYTES)
    check(OPSLIMIT_INTERACTIVE == crypto_pwhash_OPSLIMIT_INTERACTIVE.toLong())
    check(MEMLIMIT_INTERACTIVE == crypto_pwhash_MEMLIMIT_INTERACTIVE)
    check(ALG_ARGON2ID13 == crypto_pwhash_argon2id_ALG_ARGON2ID13)
  }

  fun randomBytes(count: Int): ByteArray = LibsodiumRandom.buf(count).asByteArray()

  fun toBase64(bytes: ByteArray): String =
    LibsodiumUtil.toBase64(bytes.asUByteArray(), Base64Variants.ORIGINAL)

  fun fromBase64(text: String): ByteArray =
    LibsodiumUtil.fromBase64(text, Base64Variants.ORIGINAL).asByteArray()

  class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

  fun keypair(): KeyPair {
    val kp = Box.keypair()
    return KeyPair(kp.publicKey.asByteArray(), kp.secretKey.asByteArray())
  }

  /** `crypto_scalarmult_base`: the X25519 public key that belongs to a private key. */
  fun publicKeyOf(privateKey: ByteArray): ByteArray =
    ScalarMultiplication.scalarMultiplicationBase(privateKey.asUByteArray()).asByteArray()

  /** `crypto_box_seal`: anyone with the public key can seal; only the private key opens. */
  fun seal(message: ByteArray, recipientPublicKey: ByteArray): ByteArray =
    Box.seal(message.asUByteArray(), recipientPublicKey.asUByteArray()).asByteArray()

  fun sealOpen(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray =
    Box.sealOpen(sealed.asUByteArray(), publicKey.asUByteArray(), privateKey.asUByteArray()).asByteArray()

  class Encrypted(val ciphertext: ByteArray, val nonce: ByteArray)

  /** XChaCha20-Poly1305 with a fresh random nonce and no associated data, as the server does. */
  fun aeadEncrypt(plain: ByteArray, key: ByteArray): Encrypted {
    require(key.size == KEY_BYTES) { "content key must be $KEY_BYTES bytes" }
    val nonce = randomBytes(NONCE_BYTES)
    val ciphertext = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
      plain.asUByteArray(), ubyteArrayOf(), nonce.asUByteArray(), key.asUByteArray()
    )
    return Encrypted(ciphertext.asByteArray(), nonce)
  }

  /** Throws `AeadCorrupedOrTamperedDataException` (sic, from the binding) on a wrong key or tampering. */
  fun aeadDecrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
    AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
      ciphertext.asUByteArray(), ubyteArrayOf(), nonce.asUByteArray(), key.asUByteArray()
    ).asByteArray()

  /**
   * Argon2id, 32 bytes out. The caller stores `salt` and the three cost values as `kdfParams`.
   *
   * Two things every client must do identically, or the same password derives
   * different keys on web and phone:
   *
   * 1. Normalise the secret to Unicode NFKC, because one visible password can
   *    be typed as different code point sequences ("ä" precomposed, or "a"
   *    plus a combining diaeresis) depending on the keyboard.
   * 2. Hash its UTF-8 bytes, all of them. The binding's own `PasswordHash.pwhash`
   *    passes `String.length` (UTF-16 units) as the byte length, which silently
   *    truncates any non-ASCII password, so the native function is called
   *    directly here with the real byte length. Found by the Kotlin-to-Node
   *    interop test, 17 September 2026.
   */
  /**
   * `crypto_kdf_derive_from_key`: a subkey from a 32-byte master key, named by a number and an eight-character
   * context. Deterministic, and cheap: the slow part (Argon2id) has already been paid for the master key. Two
   * subkeys of one master are unrelated to each other, which is the whole point of the split sign-in scheme.
   */
  fun deriveSubkey(masterKey: ByteArray, subkeyId: Long, context: String): ByteArray {
    require(masterKey.size == KEY_BYTES) { "master key must be $KEY_BYTES bytes" }
    val ctx = context.toByteArray(Charsets.US_ASCII)
    require(ctx.size == KDF_CONTEXT_BYTES) { "context must be exactly $KDF_CONTEXT_BYTES ASCII characters" }
    initialize()
    val out = ByteArray(KEY_BYTES)
    val rc = LibsodiumInitializer.sodiumJna.crypto_kdf_derive_from_key(out, KEY_BYTES, subkeyId, ctx, masterKey)
    check(rc == 0) { "crypto_kdf_derive_from_key failed" }
    return out
  }

  fun deriveKey(
    secret: String,
    salt: ByteArray,
    opslimit: Long = OPSLIMIT_INTERACTIVE,
    memlimit: Int = MEMLIMIT_INTERACTIVE,
    algorithm: Int = ALG_ARGON2ID13,
  ): ByteArray {
    require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
    initialize()
    val normalised = Normalizer.normalize(secret, Normalizer.Form.NFKC)
    val byteLength = normalised.toByteArray(Charsets.UTF_8).size.toLong()
    val out = ByteArray(KEY_BYTES)
    val rc = LibsodiumInitializer.sodiumJna.crypto_pwhash(out, KEY_BYTES.toLong(), normalised, byteLength, salt, opslimit, memlimit.toLong(), algorithm)
    check(rc == 0) { "crypto_pwhash failed (out of memory?)" }
    return out
  }
}

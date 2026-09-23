package me.paxana.abcmailbox.crypto

/**
 * The split sign-in scheme (API PR #114): the password never reaches the server.
 *
 * One slow derivation (Argon2id, with the account's salt and recipe) gives a master key, and two cheap ones give
 * two unrelated keys from it: the **wrap key** locks the private key and never leaves the device; the **auth key**
 * is sent to the server *as the password*. The server hashes and compares it exactly as it always did, and never
 * sees anything that opens a letter. Knowing either key tells nobody the other, nor the password.
 *
 * Every client must match this byte for byte (the vector is in `SplitAuthTest`): NFKC-normalised UTF-8 bytes of the
 * password into `crypto_pwhash`, then `crypto_kdf_derive_from_key` with id 1 and context `abcwrap_` for the wrap key,
 * id 2 and `abcauth_` for the auth key. The auth key travels as standard base64 with padding, 44 characters.
 */
object SplitAuth {
  const val WRAP_ID = 1L
  const val AUTH_ID = 2L
  const val WRAP_CONTEXT = "abcwrap_"
  const val AUTH_CONTEXT = "abcauth_"

  /** Both keys. Call [wipe] the moment they have been used; the auth key is a credential and the wrap key opens everything. */
  class Keys(val wrapKey: ByteArray, val authKey: ByteArray) {
    /** What is sent as `password`. */
    val authKeyBase64: String get() = Sodium.toBase64(authKey)
    fun wipe() { wrapKey.fill(0); authKey.fill(0) }
  }

  /** From a typed password. The master key exists only inside this call. */
  fun derive(password: String, salt: ByteArray, params: KdfParams = KdfParams()): Keys {
    val master = params.derive(password, salt)
    return try { fromMaster(master) } finally { master.fill(0) }
  }

  fun fromMaster(master: ByteArray) = Keys(Sodium.deriveSubkey(master, WRAP_ID, WRAP_CONTEXT), Sodium.deriveSubkey(master, AUTH_ID, AUTH_CONTEXT))

  /** The server checks this shape before anything else: 44 characters of standard base64, decoding to 32 bytes. */
  fun looksLikeAuthKey(text: String): Boolean = text.length == 44 && runCatching { Sodium.fromBase64(text).size == 32 }.getOrDefault(false)
}

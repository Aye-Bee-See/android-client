package me.paxana.abcmailbox.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Encrypts small blobs for local storage. Abstracted so tests can use a no-op version. */
interface SecretCipher {
  fun encrypt(plain: ByteArray): ByteArray
  fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore. The key material
 * never leaves the secure hardware (or the system's keystore process on
 * devices without it); the app only ever asks it to encrypt or decrypt.
 *
 * Output layout: 12-byte IV followed by ciphertext and tag. A fresh IV per
 * encryption is mandatory for GCM; the cipher generates it.
 *
 * If the key is gone (the user cleared app data, or the device was restored)
 * decryption throws, and the caller treats that as "signed out".
 */
class KeystoreSecretCipher @Inject constructor() : SecretCipher {

  private val alias = "abcmailbox.session"

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  override fun encrypt(plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    return cipher.iv + cipher.doFinal(plain)
  }

  override fun decrypt(blob: ByteArray): ByteArray {
    require(blob.size > IV_BYTES) { "blob too short" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
    return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
  }

  private companion object {
    const val IV_BYTES = 12
  }
}

package me.paxana.abcmailbox.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class SodiumTest {

  @Before
  fun init() = Sodium.initialize()

  @Test
  fun `aead round trip and tamper detection`() {
    val key = Sodium.randomBytes(Sodium.KEY_BYTES)
    val plain = "Dear Bill, the tomatoes are in.".toByteArray()
    val enc = Sodium.aeadEncrypt(plain, key)
    assertEquals(Sodium.NONCE_BYTES, enc.nonce.size)
    assertEquals(plain.size + 16, enc.ciphertext.size) // Poly1305 tag is 16 bytes
    assertArrayEquals(plain, Sodium.aeadDecrypt(enc.ciphertext, enc.nonce, key))

    val tampered = enc.ciphertext.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
    assertThrows(Exception::class.java) { Sodium.aeadDecrypt(tampered, enc.nonce, key) }
    assertThrows(Exception::class.java) { Sodium.aeadDecrypt(enc.ciphertext, enc.nonce, Sodium.randomBytes(32)) }
  }

  @Test
  fun `sealed box round trip`() {
    val kp = Sodium.keypair()
    val contentKey = Sodium.randomBytes(Sodium.KEY_BYTES)
    val sealed = Sodium.seal(contentKey, kp.publicKey)
    assertEquals(contentKey.size + 48, sealed.size) // ephemeral public key (32) + tag (16)
    assertArrayEquals(contentKey, Sodium.sealOpen(sealed, kp.publicKey, kp.privateKey))
    val other = Sodium.keypair()
    assertThrows(Exception::class.java) { Sodium.sealOpen(sealed, other.publicKey, other.privateKey) }
  }

  @Test
  fun `argon2id is deterministic for the same salt and differs otherwise`() {
    val salt = Sodium.randomBytes(Sodium.SALT_BYTES)
    val a = Sodium.deriveKey("correct horse", salt)
    val b = Sodium.deriveKey("correct horse", salt)
    val c = Sodium.deriveKey("correct horse", Sodium.randomBytes(Sodium.SALT_BYTES))
    val d = Sodium.deriveKey("wrong horse", salt)
    assertEquals(Sodium.KEY_BYTES, a.size)
    assertArrayEquals(a, b)
    assertNotEquals(a.toList(), c.toList())
    assertNotEquals(a.toList(), d.toList())
  }

  @Test
  fun `base64 is the standard alphabet with padding`() {
    val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x00, 0x01)
    assertEquals("+/8AAQ==", Sodium.toBase64(bytes))
    assertArrayEquals(bytes, Sodium.fromBase64("+/8AAQ=="))
  }
}

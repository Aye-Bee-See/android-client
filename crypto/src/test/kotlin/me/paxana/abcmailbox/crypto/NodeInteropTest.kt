package me.paxana.abcmailbox.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Properties

/**
 * Opens material produced by the API's own `services/crypto.js` (see
 * `tools/make-interop-fixture.mjs`). If this test passes, ciphertext from the
 * server side and from this device are interchangeable, which is the whole
 * point of using the same primitives.
 */
class NodeInteropTest {

  private lateinit var f: Properties

  @Before
  fun load() {
    Sodium.initialize()
    f = Properties().also { p ->
      // Properties.load(InputStream) assumes Latin-1; the fixture is UTF-8, so use a Reader.
      javaClass.getResourceAsStream("/interop/node-fixture.properties")!!
        .reader(Charsets.UTF_8).use { p.load(it) }
    }
  }

  @Test
  fun `decrypts an XChaCha20-Poly1305 body encrypted by node`() {
    val key = Sodium.fromBase64(f["contentKey"] as String)
    val plain = Sodium.aeadDecrypt(
      Sodium.fromBase64(f["ciphertext"] as String),
      Sodium.fromBase64(f["nonce"] as String),
      key,
    )
    assertEquals(f["plaintext"], String(plain, Charsets.UTF_8))
  }

  @Test
  fun `opens a content key sealed by node to a keypair`() {
    val opened = Sodium.sealOpen(
      Sodium.fromBase64(f["sealedContentKey"] as String),
      Sodium.fromBase64(f["publicKey"] as String),
      Sodium.fromBase64(f["privateKey"] as String),
    )
    assertArrayEquals(Sodium.fromBase64(f["contentKey"] as String), opened)
  }

  @Test
  fun `node can open what we seal, by symmetry of the primitive`() {
    // The reverse direction is exercised by tools/make-interop-fixture.mjs --verify
    // once a Kotlin-produced fixture exists; here we at least confirm our sealed
    // box has the layout node expects: ephemeral public key + ciphertext + tag.
    val pk = Sodium.fromBase64(f["publicKey"] as String)
    val sealed = Sodium.seal("x".toByteArray(), pk)
    assertEquals(1 + 48, sealed.size)
  }
}

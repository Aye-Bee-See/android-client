package me.paxana.abcmailbox.crypto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class KdfParamsTest {

  @Before
  fun init() = Sodium.initialize()

  @Test
  fun `default JSON matches the schema agreed with the web client`() {
    // The exact example from the API README; if this changes, both clients must change together.
    assertEquals("""{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}""", Json.encodeToString(KdfParams()))
  }

  @Test
  fun `parses what the web client writes, with or without the optional fields`() {
    val full = Json.decodeFromString<KdfParams>("""{"kdf":"argon2id","alg":2,"opslimit":3,"memlimit":268435456}""")
    assertEquals(3L, full.opslimit)
    assertEquals(268_435_456, full.memlimit)
    val minimal = Json { ignoreUnknownKeys = true }.decodeFromString<KdfParams>("""{"kdf":"argon2id"}""")
    assertEquals(KdfParams(), minimal)
  }

  @Test
  fun `rejects a kdf this client cannot run`() {
    assertThrows(IllegalArgumentException::class.java) { Json.decodeFromString<KdfParams>("""{"kdf":"scrypt","N":1024}""") }
  }

  @Test
  fun `derives with its own costs`() {
    val salt = Sodium.randomBytes(Sodium.SALT_BYTES)
    val params = KdfParams(opslimit = 1, memlimit = 16_777_216)
    assertArrayEquals(params.derive("pw", salt), Sodium.deriveKey("pw", salt, opslimit = 1, memlimit = 16_777_216))
  }
}

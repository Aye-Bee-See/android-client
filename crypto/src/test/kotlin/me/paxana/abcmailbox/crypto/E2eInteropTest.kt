package me.paxana.abcmailbox.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Node to Kotlin: everything in `e2e-fixture.json` was produced by libsodium.js
 * (sumo build) the way a browser client would. If these pass, an account made
 * on the web unlocks on the phone and a letter sealed there opens here.
 */
class E2eInteropTest {
  private lateinit var f: JsonObject
  private fun s(vararg path: String): String = path.dropLast(1).fold(f) { o, k -> o[k]!!.jsonObject }[path.last()]!!.jsonPrimitive.content
  private fun wrapped(name: String): Triple<String, String, KdfParams> {
    val o = f[name]!!.jsonObject
    return Triple(o["wrapped"]!!.jsonPrimitive.content, o["salt"]!!.jsonPrimitive.content, Json.decodeFromJsonElement<KdfParams>(o["params"]!!))
  }

  @Before
  fun load() {
    Sodium.initialize()
    f = Json.parseToJsonElement(javaClass.getResourceAsStream("/interop/e2e-fixture.json")!!.reader(Charsets.UTF_8).readText()).jsonObject
  }

  @Test
  fun `unlocks a private key node wrapped under a password with argon2id`() {
    val (w, salt, params) = wrapped("passwordWrapped")
    val kp = AccountKeys.unlockWithPassword(s("publicKey"), w, s("password"), salt, params)
    assertEquals(s("privateKey"), Sodium.toBase64(kp.privateKey))
    assertThrows(WrongSecretException::class.java) { AccountKeys.unlockWithPassword(s("publicKey"), w, "not the password", salt, params) }
  }

  @Test
  fun `a non-ASCII password typed in decomposed form opens the same key`() {
    // The fixture's password has accents, Cyrillic, and Chinese; this is the NFD spelling of it.
    val (w, salt, params) = wrapped("passwordWrapped")
    assertNotEquals(s("password"), s("passwordDecomposed"))
    val kp = AccountKeys.unlockWithPassword(s("publicKey"), w, s("passwordDecomposed"), salt, params)
    assertEquals(s("privateKey"), Sodium.toBase64(kp.privateKey))
  }

  @Test
  fun `a recovery code typed with dashes and lower case opens the same key`() {
    val (w, salt, params) = wrapped("recoveryWrapped")
    val kp = AccountKeys.unlockWithCode(s("publicKey"), w, s("recoveryCodeAsTyped"), salt, params)
    assertEquals(s("privateKey"), Sodium.toBase64(kp.privateKey))
  }

  @Test
  fun `opens the writer and group envelopes and decrypts body, note, and file`() {
    val me = Sodium.KeyPair(Sodium.fromBase64(s("publicKey")), Sodium.fromBase64(s("privateKey")))
    val group = Sodium.KeyPair(Sodium.fromBase64(s("groupPublicKey")), Sodium.fromBase64(s("groupPrivateKey")))
    val envelopes = f["letter"]!!.jsonObject["envelopes"]!!.jsonArray.map { it.jsonObject }
    val mine = envelopes.first { it["readerType"]!!.jsonPrimitive.content == "user" }
    val theirs = envelopes.first { it["readerType"]!!.jsonPrimitive.content == "chapter" }
    assertEquals(1, theirs["keyVersion"]!!.jsonPrimitive.int)

    val key = LetterCipher.openEnvelope(mine["wrappedKey"]!!.jsonPrimitive.content, me)
    assertArrayEquals(key, LetterCipher.openEnvelope(theirs["wrappedKey"]!!.jsonPrimitive.content, group))
    assertThrows(CannotOpenException::class.java) { LetterCipher.openEnvelope(theirs["wrappedKey"]!!.jsonPrimitive.content, me) }

    assertEquals(s("letter", "body"), LetterCipher.decryptText(s("letter", "ciphertext"), s("letter", "nonce"), key))
    assertEquals(s("letter", "note"), LetterCipher.decryptText(s("letter", "relayNote", "ciphertext"), s("letter", "relayNote", "nonce"), key))
    assertArrayEquals(Sodium.fromBase64(s("file", "plain")), LetterCipher.decryptFile(Sodium.fromBase64(s("file", "ciphertext")), s("file", "nonce"), key))
  }

  @Test
  fun `opens the recovery challenge and hashes a token like the server expects`() {
    val me = Sodium.KeyPair(Sodium.fromBase64(s("publicKey")), Sodium.fromBase64(s("privateKey")))
    assertEquals(s("recovery", "challenge"), AccountKeys.openChallenge(s("recovery", "sealedChallenge"), me))
    assertEquals(s("tokenHash", "sha256"), SecretCodes.hashHex(s("tokenHash", "token").lowercase().chunked(4).joinToString("-")))
  }
}

package me.paxana.abcmailbox.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Kotlin to Node: writes `build/interop/kotlin-fixture.json` with material made
 * by this module; `node tools/verify-kotlin-fixture.mjs` then opens it with
 * libsodium.js. Also the round-trip tests for the flows themselves.
 */
class KotlinToNodeFixtureTest {

  @Before fun init() = Sodium.initialize()

  @Test
  fun `writes a fixture for the node verifier`() {
    val password = "pässword with spaces"
    val recoveryCode = SecretCodes.generate()
    val (kp, fields) = AccountKeys.create(password, recoveryCode)
    val group = Sodium.keypair()
    val body = "Dear Jane, greetings from the phone. Ünïcödé survives."
    val note = "Please print single-sided."
    val letter = LetterCipher.encrypt(body, note, listOf(
      Reader(Reader.USER, 7, fields.publicKey),
      Reader(Reader.CHAPTER, 1, Sodium.toBase64(group.publicKey), keyVersion = 3),
    ))
    val fileBytes = Sodium.randomBytes(2048)
    val (fileCipher, fileNonce) = LetterCipher.encryptFile(fileBytes, letter.contentKey)

    fun wrappedJson(w: WrappedKey) = buildJsonObject { put("wrapped", w.wrapped); put("salt", w.salt); put("params", Json.encodeToJsonElement(w.params)) }
    val out = buildJsonObject {
      put("password", password); put("recoveryCode", recoveryCode)
      put("publicKey", fields.publicKey); put("privateKey", Sodium.toBase64(kp.privateKey))
      put("groupPublicKey", Sodium.toBase64(group.publicKey)); put("groupPrivateKey", Sodium.toBase64(group.privateKey))
      put("passwordWrapped", wrappedJson(fields.password)); put("recoveryWrapped", wrappedJson(fields.recovery))
      put("letter", buildJsonObject {
        put("body", body); put("note", note)
        put("ciphertext", letter.body.ciphertext); put("nonce", letter.body.nonce)
        put("relayNote", buildJsonObject { put("ciphertext", letter.relayNote!!.ciphertext); put("nonce", letter.relayNote!!.nonce) })
        put("contentKey", Sodium.toBase64(letter.contentKey))
        put("envelopes", buildJsonArray {
          letter.envelopes.forEach { e -> add(buildJsonObject { put("readerType", e.readerType); put("readerId", e.readerId); put("wrappedKey", e.wrappedKey); e.keyVersion?.let { put("keyVersion", it) } }) }
        })
      })
      put("file", buildJsonObject { put("plain", Sodium.toBase64(fileBytes)); put("ciphertext", Sodium.toBase64(fileCipher)); put("nonce", fileNonce) })
      put("tokenHash", buildJsonObject { put("token", recoveryCode); put("sha256", SecretCodes.hashHex(recoveryCode)) })
    }
    File("build/interop").apply { mkdirs() }.resolve("kotlin-fixture.json").writeText(out.toString())
  }

  @Test
  fun `account keys round trip and the same keypair survives re-wrapping`() {
    val code = SecretCodes.generate()
    val (kp, fields) = AccountKeys.create("first password", code)
    val viaPassword = AccountKeys.unlockWithPassword(fields.publicKey, fields.password.wrapped, "first password", fields.password.salt, fields.password.params)
    val viaCode = AccountKeys.unlockWithCode(fields.publicKey, fields.recovery.wrapped, SecretCodes.pretty(code).lowercase(), fields.recovery.salt, fields.recovery.params)
    assertArrayEquals(kp.privateKey, viaPassword.privateKey)
    assertArrayEquals(kp.privateKey, viaCode.privateKey)

    val rewrapped = AccountKeys.wrapExisting(kp, "second password", SecretCodes.generate())
    assertEquals(fields.publicKey, rewrapped.publicKey)
    assertNotEquals(fields.password.salt, rewrapped.password.salt)
    assertThrows(WrongSecretException::class.java) {
      AccountKeys.unlockWithPassword(rewrapped.publicKey, rewrapped.password.wrapped, "first password", rewrapped.password.salt, rewrapped.password.params)
    }
  }

  @Test
  fun `generated codes are well formed and distinct`() {
    val codes = List(50) { SecretCodes.generate() }
    assertTrue(codes.all { SecretCodes.isWellFormed(it) })
    assertEquals(50, codes.toSet().size)
    assertEquals(29, SecretCodes.pretty(codes.first()).length) // 24 characters + 5 dashes
  }

  @Test
  fun `an edit re-encrypts under the same content key so existing envelopes still work`() {
    val me = Sodium.keypair()
    val letter = LetterCipher.encrypt("first draft", null, listOf(Reader(Reader.USER, 1, Sodium.toBase64(me.publicKey))))
    val key = LetterCipher.openEnvelope(letter.envelopes.single().wrappedKey, me)
    val edited = LetterCipher.encryptText("second draft", key)
    assertNotEquals(letter.body.nonce, edited.nonce)
    assertEquals("second draft", LetterCipher.decryptText(edited.ciphertext, edited.nonce, LetterCipher.openEnvelope(letter.envelopes.single().wrappedKey, me)))
  }
}

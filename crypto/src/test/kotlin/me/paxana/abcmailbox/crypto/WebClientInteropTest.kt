package me.paxana.abcmailbox.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * An account made on the web must open in the app: `interop/web-account-fixture.json` was written by the web
 * client's own code (`createAccountKeys` in `letters-support-frontend/src/crypto/account.ts`, at its commit
 * `9adcce1`, 12 November 2026), not by the API's, which `E2eInteropTest` covers. The web is what most people
 * will have made their account and their recovery code with, so this is the pairing that has to hold.
 *
 * The other direction (an account made here, opened by the web's code) was run by hand the same day against
 * `build/interop/kotlin-fixture.json`, which [KotlinToNodeFixtureTest] writes; the web repository could keep that
 * check as a test of its own.
 */
class WebClientInteropTest {
  private val fixture: JsonObject = Json.parseToJsonElement(
    checkNotNull(javaClass.getResourceAsStream("/interop/web-account-fixture.json")) { "the web fixture is missing" }.bufferedReader(Charsets.UTF_8).readText(),
  ).jsonObject
  private val fields = fixture.getValue("keyFields").jsonObject
  private fun s(name: String) = fixture.getValue(name).jsonPrimitive.content
  private fun f(name: String) = fields.getValue(name).jsonPrimitive.content
  private fun params(name: String) = Json.decodeFromJsonElement(KdfParams.serializer(), fields.getValue(name))

  @Before fun init() = Sodium.initialize()

  @Test
  fun `the recovery code the web showed opens the key here, however it is typed`() {
    val shown = s("recoveryCodeAsShown")
    assertEquals("the web shows a code the way the app does: 24 characters in six groups of four", shown, SecretCodes.pretty(shown))
    assertEquals(true, SecretCodes.isWellFormed(shown))
    val expected = Sodium.fromBase64(s("privateKey"))
    // As shown, then as a person might type it back: lower case, spaces for dashes, and the letters for the digits.
    val typed = shown.lowercase().replace('-', ' ').replace('0', 'o').replace('1', 'l')
    for (code in listOf(shown, typed, SecretCodes.normalise(shown))) {
      val kp = AccountKeys.unlockWithCode(f("publicKey"), f("recoveryWrappedPrivateKey"), code, f("recoverySalt"), params("recoveryKdfParams"))
      assertArrayEquals("typed as \"$code\"", expected, kp.privateKey)
    }
  }

  @Test
  fun `another code opens nothing`() {
    assertThrows(WrongSecretException::class.java) {
      AccountKeys.unlockWithCode(f("publicKey"), f("recoveryWrappedPrivateKey"), "0000-0000-0000-0000-0000-0000", f("recoverySalt"), params("recoveryKdfParams"))
    }
  }

  @Test
  fun `the password signs in here with the auth key the web sent, and opens the key`() {
    val keys = SplitAuth.derive(s("password"), Sodium.fromBase64(f("kdfSalt")), params("kdfParams"))
    assertEquals("what the server checks", s("authKey"), keys.authKeyBase64)
    val kp = AccountKeys.unlockWithWrapKey(f("publicKey"), f("wrappedPrivateKey"), keys.wrapKey)
    assertArrayEquals(Sodium.fromBase64(s("privateKey")), kp.privateKey)
  }

  /**
   * A reply a group recorded on the website as a photograph alone: an empty body, encrypted all the same, and the
   * file under the same content key (`interop/web-photo-reply-fixture.json`, made by the web client's
   * `encryptLetter("")` and `encryptFile`, 15 November 2026). An empty body's ciphertext is sixteen bytes of
   * authentication and nothing else; it has to open as "", not fail and leave the letter looking locked.
   */
  @Test
  fun `a reply that is a photograph alone, made on the web, opens here as an empty body and its file`() {
    val reply = Json.parseToJsonElement(
      checkNotNull(javaClass.getResourceAsStream("/interop/web-photo-reply-fixture.json")) { "the web photo-reply fixture is missing" }.bufferedReader(Charsets.UTF_8).readText(),
    ).jsonObject
    fun at(vararg path: String) = path.fold(reply as kotlinx.serialization.json.JsonElement) { e, k -> e.jsonObject.getValue(k) }.jsonPrimitive.content
    val writer = Sodium.KeyPair(Sodium.fromBase64(at("writer", "publicKey")), Sodium.fromBase64(at("writer", "privateKey")))
    val envelope = reply.getValue("message").jsonObject.getValue("envelopes").let { it as kotlinx.serialization.json.JsonArray }
      .first { it.jsonObject.getValue("readerType").jsonPrimitive.content == "user" }.jsonObject.getValue("wrappedKey").jsonPrimitive.content
    val contentKey = LetterCipher.openEnvelope(envelope, writer)
    assertEquals("nothing was typed", "", LetterCipher.decryptText(at("message", "ciphertext"), at("message", "nonce"), contentKey))
    assertArrayEquals("the photograph, byte for byte", Sodium.fromBase64(at("file", "plain")), LetterCipher.decryptFile(Sodium.fromBase64(at("file", "ciphertext")), at("file", "nonce"), contentKey))
  }
}

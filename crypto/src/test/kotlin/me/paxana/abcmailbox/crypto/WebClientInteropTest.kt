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
}

package me.paxana.abcmailbox.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAuthTest {
  /** The vector from the API's `test/auth-split.test.js` and the proposal: master = bytes 00…1f. */
  private val master = ByteArray(32) { it.toByte() }

  @Test
  fun `the two derivations match the API's vector byte for byte`() {
    assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", Sodium.toBase64(master))
    val keys = SplitAuth.fromMaster(master)
    assertEquals("NFKvOp5duAjQ7QmoxriVlK2aftNJI7xkGi/wooDCMcI=", Sodium.toBase64(keys.wrapKey))
    assertEquals("wjrINHoPKZiRPnOiURiE/mvNm6+UZXEkNDE/lgfPOWo=", keys.authKeyBase64)
    assertEquals("44 characters of standard base64 with padding", 44, keys.authKeyBase64.length)
    assertTrue(SplitAuth.looksLikeAuthKey(keys.authKeyBase64)); assertFalse(SplitAuth.looksLikeAuthKey("correct horse battery staple"))
  }

  @Test
  fun `from a password, the same salt and recipe give the same keys, and a wrong password opens nothing`() {
    val salt = Sodium.randomBytes(Sodium.SALT_BYTES)
    val a = SplitAuth.derive("Tomatoes by Äugust", salt); val b = SplitAuth.derive("Tomatoes by Äugust", salt) // NFKC: the same password
    assertArrayEquals(a.wrapKey, b.wrapKey); assertEquals(a.authKeyBase64, b.authKeyBase64)
    assertFalse("the two keys are unrelated", a.wrapKey.contentEquals(a.authKey))

    val kp = Sodium.keypair()
    val (wrapped, authKey) = AccountKeys.wrapForSplitPassword(kp, "Tomatoes by Äugust")
    assertEquals(44, authKey.length)
    val again = SplitAuth.derive("Tomatoes by Äugust", Sodium.fromBase64(wrapped.salt), wrapped.params)
    assertArrayEquals(kp.privateKey, AccountKeys.unlockWithWrapKey(Sodium.toBase64(kp.publicKey), wrapped.wrapped, again.wrapKey).privateKey)
    val wrong = SplitAuth.derive("tomatoes by august", Sodium.fromBase64(wrapped.salt), wrapped.params)
    assertThrows(WrongSecretException::class.java) { AccountKeys.unlockWithWrapKey(Sodium.toBase64(kp.publicKey), wrapped.wrapped, wrong.wrapKey) }
    // And the auth key is not the wrap key: a server holding the auth key cannot open the box.
    assertThrows(WrongSecretException::class.java) { KeyWrapping.unwrapWithKey(wrapped.wrapped, again.authKey) }
  }

  @Test
  fun `a split account's recovery wrap is the plain kind, opened by the code itself`() {
    val (kp, split) = AccountKeys.createSplit("Tomatoes by August", "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ")
    val r = split.fields.recovery
    assertArrayEquals(kp.privateKey, AccountKeys.unlockWithCode(split.fields.publicKey, r.wrapped, "abcd efgh jklm npqr stuv wxyz", r.salt, r.params).privateKey)
  }
}

package me.paxana.abcmailbox.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed codes (recovery codes and claim tokens) are read the way the API reads them (README, "Typed codes"): upper
 * case, letters and digits only, then O as 0 and I, L as 1. The normalised text is what goes into the key derivation,
 * so a client that folds differently derives a different key from the right code. The web client and iOS pin the
 * same cases (web `crypto.test.ts`, ios-client #12).
 */
class SecretCodesTest {
  private val printed = "0123-4567-89AB-CDEF-GHJK-MNPQ"

  @Test
  fun `look-alikes are read as the digits they are mistaken for`() {
    assertEquals("0123456789ABCDEFGHJKMNPQ", SecretCodes.normalise("O123-4567-89AB-CDEF-GHJK-MNPQ"))
    assertEquals("lower case folds too", "01131567", SecretCodes.normalise("o1i3 l567"))
    assertEquals("dashes, dots and spaces go; case does not matter", "0123456789AB", SecretCodes.normalise(" 0123.4567-89ab "))
    assertEquals("the web client's own vectors", "ABCDEFGH1JK1MN0P", SecretCodes.normalise("abcd-efgh ijkl.mnop"))
    assertEquals("00111", SecretCodes.normalise("0O1IL"))
    assertTrue("the typo is not reported as an invalid code", SecretCodes.isWellFormed("O123-4567-89AB-CDEF-GHJK-MNPQ"))
    assertFalse("U is not a look-alike of anything, so it stays wrong", SecretCodes.isWellFormed("U123-4567-89AB-CDEF-GHJK-MNPQ"))
    assertEquals(printed, SecretCodes.pretty("O123 4567 89ab cdef ghjk mnpq"))
    assertEquals("a claim token is found either way", SecretCodes.hashHex(printed), SecretCodes.hashHex("O123-4567-89AB-CDEF-GHJK-MNPQ"))
  }

  @Test
  fun `generated codes never contain a look-alike, so folding changes nothing typed correctly`() {
    repeat(200) {
      val code = SecretCodes.generate()
      assertEquals(code, SecretCodes.normalise(code))
      assertFalse(code.any { it in "ILOU" })
    }
  }

  @Test
  fun `a key wrapped under the recovery code opens when it is typed with look-alikes`() {
    val keyPair = Sodium.keypair()
    val fields = AccountKeys.wrapExisting(keyPair, "a long enough password", printed)
    for (typed in listOf("O123-4567-89AB-CDEF-GHJK-MNPQ", "o123 4567 89ab cdef ghjk mnpq", printed)) {
      val opened = AccountKeys.unlockWithCode(fields.publicKey, fields.recovery.wrapped, typed, fields.recovery.salt, fields.recovery.params)
      assertArrayEquals(typed, keyPair.privateKey, opened.privateKey)
    }
    assertThrows(Exception::class.java) {
      AccountKeys.unlockWithCode(fields.publicKey, fields.recovery.wrapped, "1123-4567-89AB-CDEF-GHJK-MNPQ", fields.recovery.salt, fields.recovery.params)
    }
  }
}

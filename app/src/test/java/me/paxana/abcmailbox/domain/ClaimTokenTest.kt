package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimTokenTest {
  private val good = "DJ69G5K7XBMYFWW4P4PYTJ8C"

  @Test
  fun `entry is forgiving about case, dashes, and spaces`() {
    assertEquals(good, ClaimToken.normalise("dj69-g5k7 xbmy-fww4-p4py-tj8c "))
    assertTrue(ClaimToken.isWellFormed("dj69-g5k7-xbmy-fww4-p4py-tj8c"))
    assertEquals("DJ69-G5K7-XBMY-FWW4-P4PY-TJ8C", ClaimToken.pretty(good))
  }

  @Test
  fun `problems are explained before a request is spent`() {
    assertNull(ClaimToken.problem(good, TestStrings()))
    assertEquals("Enter the token your group gave you.", ClaimToken.problem("  ", TestStrings()))
    assertEquals("That is 23 characters; a token has 24.", ClaimToken.problem(good.dropLast(1), TestStrings()))
    assertEquals("That is 25 characters; a token has only 24.", ClaimToken.problem(good + "A", TestStrings()))
    assertEquals("Tokens never contain the character U. Check for a look-alike.", ClaimToken.problem(good.replaceRange(0, 1, "U"), TestStrings()))
    assertFalse(ClaimToken.isWellFormed(good.replaceRange(3, 4, "U")))
  }

  @Test
  fun `an O typed for a zero and an I or L for a one are read as the digits, as the API and the other clients read them`() {
    val withZeroAndOne = "0123-4567-89AB-CDEF-GHJK-MNPQ"
    assertTrue(ClaimToken.isWellFormed("O123-4567-89ab-cdef-ghjk-mnpq"))
    assertEquals(ClaimToken.normalise(withZeroAndOne), ClaimToken.normalise("O123-4567-89ab-cdef-ghjk-mnpq"))
    assertEquals("0123", ClaimToken.normalise("oi23"))
  }
}

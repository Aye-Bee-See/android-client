package me.paxana.abcmailbox.domain

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
    assertNull(ClaimToken.problem(good))
    assertEquals("Enter the token your group gave you.", ClaimToken.problem("  "))
    assertEquals("That is 23 characters; a token has 24.", ClaimToken.problem(good.dropLast(1)))
    assertEquals("That is 25 characters; a token has only 24.", ClaimToken.problem(good + "A"))
    assertTrue(ClaimToken.problem(good.replaceRange(0, 1, "O"))!!.contains("never contain the character O"))
    assertFalse(ClaimToken.isWellFormed(good.replaceRange(3, 4, "L")))
  }
}

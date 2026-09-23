package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeTest {
  @Test
  fun `entry is forgiving the way the server is, look-alikes included`() {
    assertEquals("7Q4M2XKD9HB0", InviteCode.normalise(" 7q4m-2xkd 9hbO "))
    assertEquals("the letters O, I and L read as the digits they look like", "011", InviteCode.normalise("o i L"))
    assertTrue(InviteCode.isWellFormed("7q4m-2xkd-9hbt"))
    assertFalse("U is not in the alphabet", InviteCode.isWellFormed("7q4m-2xkd-9hbu"))
    assertFalse(InviteCode.isWellFormed("7q4m-2xkd"))
    assertEquals("7Q4M-2XKD-9HBT", InviteCode.pretty("7q4m2xkd9hbt"))
  }

  @Test
  fun `the slip's link carries the code as it is printed`() {
    assertEquals("https://letters.support/join?code=7Q4M-2XKD-9HBT", InviteCode.link("7q4m2xkd9hbt"))
  }

  @Test
  fun `the first problem with what was typed is said as a sentence, and nothing is sent for it`() {
    val strings = TestStrings()
    assertEquals("Enter the code on your slip.", InviteCode.problem("  ", strings))
    assertEquals("Codes never contain the character U. Check for a look-alike.", InviteCode.problem("7Q4M-2XKD-9HBU", strings))
    assertEquals("That is 8 characters; a code has 12.", InviteCode.problem("7Q4M-2XKD", strings))
    assertEquals("That is 13 characters; a code has only 12.", InviteCode.problem("7Q4M-2XKD-9HBT7", strings))
    assertNull(InviteCode.problem("7q4m 2xkd 9hbt", strings))
  }
}

package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenNameTest {
  private val strings = TestStrings()

  @Test
  fun `one space between words, as the server stores it`() {
    assertEquals("James Hollow", PenName.normalise("  James   Hollow "))
  }

  @Test
  fun `the shape is checked before the server is asked`() {
    assertEquals("A pen name has at least 3 characters.", PenName.problem("Jo", strings))
    assertEquals("A pen name has at most 40 characters.", PenName.problem("J".repeat(41), strings))
    assertEquals("A pen name starts with a letter.", PenName.problem("7 Hollow", strings))
    assertEquals("@ cannot be in a pen name.", PenName.problem("James @Hollow", strings))
    assertNull(PenName.problem("James Hollow", strings))
    assertNull("letters in any script, digits, hyphens, apostrophes and dots", PenName.problem("Zoë O'Brien-Núñez Jr. 2", strings))
    assertNull(PenName.problem("Анна Каренина", strings))
  }

  @Test
  fun `two parts read as a name in a mail room, and are encouraged, not required`() {
    assertTrue(PenName.hasTwoParts("James Hollow")); assertFalse(PenName.hasTwoParts("Hollow"))
  }
}

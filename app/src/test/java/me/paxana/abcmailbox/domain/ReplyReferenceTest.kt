package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyReferenceTest {
  private val strings = TestStrings()

  @Test
  fun `typed with or without dashes or spaces, printed as four, four and the check digit`() {
    assertEquals("482719356", ReplyReference.normalise("4827-1935-6")); assertEquals("482719356", ReplyReference.normalise(" 4827 1935 6 "))
    assertEquals("4827-1935-6", ReplyReference.pretty("482719356"))
    assertEquals("a wrong length is shown as typed", "48271935", ReplyReference.pretty("4827-1935"))
  }

  @Test
  fun `the shape is checked on the phone, the check digit by the server`() {
    assertEquals("Enter the number at the top of the reply.", ReplyReference.problem(" - ", strings))
    assertEquals("The number is digits only; O is not one.", ReplyReference.problem("4827-1935-O", strings))
    assertEquals("That is 8 digits; a reference has 9.", ReplyReference.problem("4827-1935", strings))
    assertEquals("That is 1 digit; a reference has 9.", ReplyReference.problem("4", strings))
    assertNull(ReplyReference.problem("4827 1935 6", strings))
  }
}

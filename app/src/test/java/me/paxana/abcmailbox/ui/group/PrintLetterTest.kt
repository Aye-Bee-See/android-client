package me.paxana.abcmailbox.ui.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintLetterTest {
  @Test
  fun `the page carries the footer under a rule, escaped, and nothing else machine-readable`() {
    val html = PrintLetter.html("Dear friend <3", "Write back to James Hollow, c/o PDX ABC & co. Reference 4827-1935-6: please write this number at the top of your reply.")
    assertTrue(html.contains("<p>Dear friend &lt;3</p>"))
    assertTrue(html.contains("""<p class="footer">Write back to James Hollow, c/o PDX ABC &amp; co. Reference 4827-1935-6"""))
    assertFalse("no footer, no paragraph for it", PrintLetter.html("Dear friend", null).contains("<p class=\"footer\">"))
  }
}

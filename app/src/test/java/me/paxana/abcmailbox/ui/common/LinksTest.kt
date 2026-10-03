package me.paxana.abcmailbox.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a directory field may be opened as: the web and mail, nothing else. */
class LinksTest {
  @Test
  fun `web addresses are opened as they are, or as https when no scheme was written`() {
    assertEquals("https://abcportland.org/", directoryLink("https://abcportland.org/"))
    assertEquals("http://abcportland.org", directoryLink("http://abcportland.org"))
    assertEquals("https://abcportland.org", directoryLink(" abcportland.org "))
    assertEquals("https://mastodon.social/@abcportland", directoryLink("mastodon.social/@abcportland"))
  }

  @Test
  fun `an email address opens the mail app`() {
    assertEquals("mailto:hello@abcportland.org", directoryLink("hello@abcportland.org"))
    assertEquals("mailto:hello@abcportland.org", directoryLink("mailto:hello@abcportland.org"))
  }

  @Test
  fun `any other scheme, this app's own included, and text that is no address, are not links`() {
    assertNull(directoryLink("tel:+15035550100"))
    assertNull(directoryLink("abcmailbox://join?code=7Q4M2XKD9HBT"))
    assertNull(directoryLink("intent://scan/#Intent;scheme=zxing;end"))
    assertNull(directoryLink("ask at the info table"))
    assertNull(directoryLink(""))
    assertNull(directoryLink("https://"))
  }
}

package me.paxana.abcmailbox.data.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search text is plain text to find, as on the server since API #159: `%` and `_` are characters, not wildcards. */
class SearchTextTest {
  @Test
  fun `the wildcards and the escape itself are escaped, and nothing else changes`() {
    assertEquals("\\%", "%".likeLiteral())
    assertEquals("ann\\_marie", "ann_marie".likeLiteral())
    assertEquals("a\\\\b", "a\\b".likeLiteral())
    assertEquals("jane smith", "jane smith".likeLiteral())
  }
}

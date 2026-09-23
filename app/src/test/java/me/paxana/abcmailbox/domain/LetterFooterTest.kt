package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The footer's wording is the app's, from the API's parts (its brief, 23 Sep 2026): a name, a group, a number. */
class LetterFooterTest {
  @Test
  fun `the suggested sentence, and its variants`() {
    val en = TestStrings()
    assertEquals(
      "Write back to James Hollow, c/o PDX ABC. Reference 4827-1935-6: please write this number at the top of your reply.",
      LetterFooter("James Hollow", false, 3, "PDX ABC", "4827-1935-6", false).sentence(en),
    )
    assertEquals("the group's anonymous writer: the group only", "Write back c/o PDX ABC. Reference 4827-1935-6: please write this number at the top of your reply.", LetterFooter(null, true, 3, "PDX ABC", "4827-1935-6", false).sentence(en))
    assertEquals("a facility that refuses numbers: no number at all", "Write back to James Hollow, c/o PDX ABC.", LetterFooter("James Hollow", false, 3, "PDX ABC", null, false).sentence(en))
    assertEquals("Responde a James Hollow, a través de PDX ABC. Referencia 4827-1935-6: por favor, escribe este número al principio de tu respuesta.", LetterFooter("James Hollow", false, 3, "PDX ABC", "4827-1935-6", false).sentence(TestStrings("es")))
  }
}

package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.domain.PenNameCheck
import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Test

class PenNameStateTest {
  @Test
  fun `a name refused as not_unique is said in the app's words, anything else in the server's`() {
    val taken = PenNameState(value = "Anna Hollow", check = PenNameCheck("Anna Hollow", false, "That pen name is taken.", true, reasonCode = "not_unique"))
    assertEquals("Anna Hollow is taken. A pen name once used stays with the person who used it, so choose another.", taken.message(TestStrings()))
    val other = PenNameState(value = "Admin", check = PenNameCheck("Admin", false, "That name is kept for the site.", false, reasonCode = "validation_failed"))
    assertEquals("That name is kept for the site.", other.message(TestStrings()))
    val older = PenNameState(value = "Anna Hollow", check = PenNameCheck("Anna Hollow", false, null, true))
    assertEquals("an older API, with neither", "That name is taken.", older.message(TestStrings()))
  }
}

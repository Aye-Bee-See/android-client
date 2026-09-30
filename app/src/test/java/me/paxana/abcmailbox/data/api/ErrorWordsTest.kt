package me.paxana.abcmailbox.data.api

import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorWordsTest {
  private val en = TestStrings()

  @Test
  fun `a 429 is worded by the app with the wait in whole minutes, never the server's English`() {
    val limited = AppError.RateLimited("Too many letters and replies. Try again in 45 minute(s).", 2700)
    assertEquals("The server has asked for a pause. Try again in 45 minutes.", limited.message(en))
    assertEquals("a few seconds is a minute", "The server has asked for a pause. Try again in 1 minute.", AppError.RateLimited(null, 20).message(en))
    assertEquals("The server has asked for a pause. Try again later.", AppError.RateLimited("Too many.", null).message(en))
  }

  @Test
  fun `a 429 in Russian takes the right plural form`() {
    assertEquals("Сервер попросил сделать паузу. Попробуйте снова через 2 минуты.", AppError.RateLimited(null, 120).message(TestStrings("ru")))
    assertEquals("Сервер попросил сделать паузу. Попробуйте снова через 5 минут.", AppError.RateLimited(null, 300).message(TestStrings("ru")))
  }

  @Test
  fun `anything else is the server's sentence, as before`() {
    assertEquals("Letter 41 was changed.", AppError.Conflict("Letter 41 was changed.", "LetterStatusError").message(en))
    assertEquals(null, AppError.Network(java.io.IOException()).message(en))
  }

  @Test
  fun `a group's block of the writer is worded by the app, not with the server's group number`() {
    val e = AppError.Forbidden("Error creating message.", "GroupBlockError", "group_block")
    assertEquals("The group that mails to this facility is not mailing letters from your account.", e.message(TestStrings()))
  }
}

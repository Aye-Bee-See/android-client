package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.FieldProblem
import me.paxana.abcmailbox.text.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Refusals worded from their codes (API PR #133), the same rules as the iOS app's; the API's sentence is the fallback. */
class FormErrorsTest {
  private val en = TestStrings()
  private val fields = mapOf("username" to "username", "email" to "email address", "penName" to "pen name", "group.name" to "group name")
  private fun refused(vararg p: FieldProblem) = AppError.Validation(p.map { it.message }, problems = p.toList())

  @Test
  fun `each problem goes under its field, in the app's words for the common codes`() {
    val e = FormErrors.of(refused(
      FieldProblem("username", "not_unique", message = "Username taken."),
      FieldProblem("penName", "length_out_of_range", 3, 40, "penName must be between 3 and 40 characters."),
      FieldProblem("email", "not_an_email", message = "Email must be in traditional email format."),
      FieldProblem("group.name", "required", message = "name cannot be null."),
    ), fields, en)
    assertEquals("That username is already taken. Choose another.", e.byField["username"])
    assertEquals("Pen name must be 3 to 40 characters.", e.byField["penName"])
    assertEquals("That does not look like an email address.", e.byField["email"])
    assertEquals("the path, not the bare name", "Group name is needed.", e.byField["group.name"])
    assertEquals("Check the fields marked in red.", e.general)
  }

  @Test
  fun `what the form does not show, and codes without the app's words, are said in the API's sentence`() {
    val e = FormErrors.of(refused(
      FieldProblem("username", "validation_failed", message = "Usernames cannot start with a digit."),
      FieldProblem("relayChapter", "unknown_reference", message = "Group 9 does not exist."),
      FieldProblem(null, "not_an_auth_key", message = "password is not an auth key."),
    ), fields, en)
    assertEquals("validation_failed means no finer code yet", "Usernames cannot start with a digit.", e.byField["username"])
    assertEquals("Group 9 does not exist. This version of the app sent something the server could not use. Update the app, then try again.", e.general)
  }

  @Test
  fun `a refusal without problems keeps the form's own wording`() {
    val e = FormErrors.of(AppError.Validation(listOf("Something is wrong.")), fields, en) { "the form's own words" }
    assertEquals(FormErrors(general = "the form's own words"), e)
  }

  @Test
  fun `the length sentence takes the right plural, in Russian too`() {
    val ru = TestStrings("ru")
    val e = FormErrors.of(refused(FieldProblem("username", "length_out_of_range", 3, 16, "x")), mapOf("username" to "имя пользователя"), ru)
    assertEquals("Поле «Имя пользователя» должно содержать от 3 до 16 символов.", e.byField["username"])
  }

  /** The group forms' own cases, with the params the API sends for them; the same as the iOS app's (ios-client #15). */
  @Test
  fun `the group forms' limits read as the app's words`() {
    fun one(field: String, code: String, min: Int? = null, max: Int? = null, message: String, label: String) =
      FormErrors.of(refused(FieldProblem(field, code, min, max, message)), mapOf(field to label), en).byField[field]
    assertEquals("Label can be at most 80 characters.", one("label", "length_out_of_range", 0, 80, "label can be at most 80 characters.", "label"))
    assertEquals("Name must be 3 to 32 characters.", one("name", "length_out_of_range", 3, 32, "Name must be between 3 and 32 characters.", "name"))
    assertEquals("Number must be from 0 to 100000.", one("lettersSentBefore", "out_of_range", 0, 100000, "x", "number"))
    assertEquals("Number must be 1 or more.", one("lettersSentBefore", "out_of_range", 1, null, "x", "number"))
    assertEquals("a maximum alone is the API's to word", "lettersSentBefore cannot be negative.", one("lettersSentBefore", "out_of_range", null, 0, "lettersSentBefore cannot be negative.", "number"))
    assertEquals("Number must be a whole number.", one("lettersSentBefore", "not_a_number", message = "lettersSentBefore must be a whole number.", label = "number"))
    assertEquals("managerNote is too long.", one("managerNote", "validation_failed", message = "managerNote is too long.", label = "note"))
  }
}

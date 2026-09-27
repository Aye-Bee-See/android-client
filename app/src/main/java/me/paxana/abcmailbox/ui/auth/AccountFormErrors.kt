package me.paxana.abcmailbox.ui.auth

import androidx.annotation.StringRes
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.domain.FormErrors
import me.paxana.abcmailbox.text.Strings

/** The fields every new-account form shows, by their API names, labelled as the refusals name them. */
internal fun accountFields(strings: Strings): Map<String, String> = mapOf(
  "username" to strings.get(R.string.field_username),
  "email" to strings.get(R.string.field_email),
  "name" to strings.get(R.string.field_name),
  "penName" to strings.get(R.string.field_pen_name),
)

/**
 * A refused submit of an account form (API PR #133): each problem under its field, and one line for the rest. [kept]
 * is the form's own sentence for "the code or invitation was not used up", said after any 400 that names fields, as
 * the whole-form message already said it. [fallback] words a refusal with no field problems, as the form did before.
 */
internal fun AppError.forForm(fields: Map<String, String>, strings: Strings, @StringRes kept: Int?, fallback: (AppError) -> String): FormErrors {
  val errors = FormErrors.of(this, fields, strings, fallback)
  if (this !is AppError.Validation || problems.isEmpty() || kept == null) return errors
  return errors.copy(general = listOfNotNull(errors.general, strings.get(kept)).joinToString(" "))
}

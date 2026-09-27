package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.FieldProblem
import me.paxana.abcmailbox.data.api.message
import me.paxana.abcmailbox.text.Strings

/**
 * A form's view of a refusal (API PR #133): a sentence under each field the form shows, and one line for everything
 * else. The API never translates: the words for the common codes are the app's, and the API's sentence is the
 * fallback, always for `validation_failed`, which means "no finer code yet". The same rules as the iOS app's.
 */
data class FormErrors(
  /** Keyed by the API's field path (`username`, `group.name`), as the form passes it in. */
  val byField: Map<String, String> = emptyMap(),
  /** What did not land under a field, or a pointer to the fields that did. Null when there is nothing to say. */
  val general: String? = null,
) {
  companion object {
    val None = FormErrors()

    /**
     * [fields] maps each API field path this form shows to the label it has on screen, in the reader's language.
     * [fallback] words an error that carries no field problems at all, as the form already did before.
     */
    fun of(error: AppError, fields: Map<String, String>, strings: Strings, fallback: (AppError) -> String? = { it.message(strings) }): FormErrors {
      val problems = (error as? AppError.Validation)?.problems.orEmpty()
      if (problems.isEmpty()) return FormErrors(general = fallback(error))
      val byField = linkedMapOf<String, String>()
      val rest = mutableListOf<String>()
      for (p in problems) {
        val label = p.field?.let { fields[it] }
        if (label != null) byField.getOrPut(p.field) { sentence(p, label, strings) } else rest += sentence(p, null, strings)
      }
      return FormErrors(byField, if (rest.isEmpty()) strings.get(R.string.form_check_fields) else rest.joinToString(" "))
    }

    /** The app's words for a code, or the API's sentence when it has none. */
    internal fun sentence(p: FieldProblem, label: String?, strings: Strings): String {
      val what = label?.replaceFirstChar { it.uppercaseChar() }
      return when {
        p.code == "required" && what != null -> strings.get(R.string.form_required, what)
        p.code == "length_out_of_range" && what != null && p.min != null && p.max != null -> strings.plural(R.plurals.form_length, p.max, what, p.min) // the helper puts the count (the maximum) first
        p.code == "not_unique" && label != null -> strings.get(R.string.form_not_unique, label)
        p.code == "not_an_email" -> strings.get(R.string.form_not_an_email)
        p.code == "not_a_url" -> strings.get(R.string.form_not_a_url)
        p.code == "reserved_value" && label != null -> strings.get(R.string.form_reserved, label)
        // Never the person's to fix: the app sent something the server cannot use.
        p.code == "not_an_auth_key" || p.code == "wrong_encryption_mode" -> strings.get(R.string.form_app_needs_update)
        else -> p.message
      }
    }
  }
}

package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.domain.PenName
import me.paxana.abcmailbox.domain.PenNameCheck
import me.paxana.abcmailbox.text.Strings

/** A pen name as it is typed: the shape checked at once, the server asked a moment later whether it is free. */
data class PenNameState(
  val value: String = "",
  val problem: String? = null,
  val checking: Boolean = false,
  val check: PenNameCheck? = null,
  val checkFailed: Boolean = false,
  /**
   * The name the group already gave this writer (a claim, API #168). Kept as it is, it is theirs: the public check
   * would call it taken, so it is not asked, and the server keeps it when nothing is sent.
   */
  val given: String? = null,
) {
  /** The group's name, as given or in another spelling that folds to it: nothing to check and nothing to send. */
  val keepsGiven: Boolean get() = given != null && PenName.normalise(value).equals(PenName.normalise(given), ignoreCase = true)

  /** Required when an account is made (API #168): an empty field keeps the form from going on, but is not an error until then. */
  val missing: Boolean get() = value.isBlank()

  /** The form must not go on with this name: the shape is wrong, or the server said it is taken. An unreachable check does not block; the server checks again on submit. */
  val blocks: Boolean get() = value.isNotBlank() && !keepsGiven && (problem != null || check?.available == false)
  val isError: Boolean get() = !keepsGiven && (problem != null || check?.available == false)

  /** Enough to make an account with: present, and not refused by the shape or the check. */
  val ready: Boolean get() = !missing && !blocks

  /** What to say under the field, or null for the rules. */
  fun message(strings: Strings): String? = when {
    value.isBlank() -> null
    keepsGiven -> strings.get(R.string.pen_name_given_by_group)
    problem != null -> problem
    check != null -> when {
      // The app's words where the code says why (API PR #133), in the reader's language; else the API's sentence.
      !check.available && check.reasonCode == "not_unique" -> strings.get(R.string.pen_name_taken_named, check.name)
      !check.available -> check.reason ?: strings.get(R.string.pen_name_taken)
      !check.twoParts -> strings.get(R.string.pen_name_two_parts_nudge, check.name)
      else -> strings.get(R.string.pen_name_available, check.name)
    }
    checkFailed -> strings.get(R.string.pen_name_check_failed)
    else -> null
  }
}

/**
 * Checks a pen name as it is typed (API PR #120): the shape here, at once, and whether it is free by the public
 * check, a moment after typing stops, so that the rate-limited call is not made for every character.
 */
class PenNameChecker(private val scope: CoroutineScope, private val repo: PenNameRepository, private val strings: Strings) {
  private val _state = MutableStateFlow(PenNameState())
  val state: StateFlow<PenNameState> = _state.asStateFlow()
  private var job: Job? = null

  /** The group already named this writer: the field starts with that name, kept unless changed. */
  fun prefill(given: String) {
    job?.cancel()
    _state.value = PenNameState(value = given, given = given)
  }

  fun onChange(v: String) {
    job?.cancel()
    val problem = if (v.isBlank()) null else PenName.problem(v, strings)
    _state.value = PenNameState(value = v, problem = problem, given = _state.value.given)
    if (v.isBlank() || problem != null || _state.value.keepsGiven) return
    job = scope.launch {
      delay(DEBOUNCE_MS)
      _state.update { it.copy(checking = true) }
      val r = repo.check(v)
      // Typing went on meanwhile: this answer is about an older name, and the newer one has its own check coming.
      _state.update { s -> if (s.value != v) s else when (r) { is ApiResult.Success -> s.copy(checking = false, check = r.value); is ApiResult.Failure -> s.copy(checking = false, checkFailed = true) } }
    }
  }

  companion object { const val DEBOUNCE_MS = 400L }
}

@Composable
/** [serverError] is a refusal of the saved form for this field (API PR #133), shown in place of the check's message. */
fun PenNameField(state: PenNameState, onChange: (String) -> Unit, enabled: Boolean, strings: Strings, modifier: Modifier = Modifier, label: String = stringResource(R.string.label_pen_name), serverError: String? = null) {
  OutlinedTextField(
    state.value, onChange, label = { Text(label) },
    supportingText = { Text(serverError ?: if (state.checking) stringResource(R.string.action_checking) else state.message(strings) ?: stringResource(R.string.pen_name_rules)) },
    isError = state.isError || serverError != null, singleLine = true, enabled = enabled,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Next),
    modifier = modifier.fillMaxWidth().testTag("pen-name"),
  )
}

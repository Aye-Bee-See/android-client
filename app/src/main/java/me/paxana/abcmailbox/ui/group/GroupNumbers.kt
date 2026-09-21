package me.paxana.abcmailbox.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.repo.GroupNumbers
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.KeyValue
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.SectionTitle
import javax.inject.Inject

data class GroupNumbersUiState(
  /** Loaded with null means the server does not count yet (an API from before PR #112). */
  val numbers: Loadable<GroupNumbers?> = Loadable.Loading,
  val typed: String = "",
  val busy: Boolean = false,
  val error: String? = null,
  val saved: Boolean = false,
) {
  /** A whole number, not negative, and not absurd: six digits is more letters than any group has mailed. */
  val parsed: Int? get() = typed.trim().takeIf { it.isNotEmpty() && it.length <= 6 && it.all(Char::isDigit) }?.toInt()
  val changed: Boolean get() = parsed != null && parsed != (numbers as? Loadable.Loaded)?.value?.before
  val canSave: Boolean get() = !busy && changed
}

/**
 * A group's public numbers (API PR #112). The server counts what the group marks mailed here; the one thing a
 * person types is what the group mailed before it used the site. The total is published only from twenty,
 * so that a small group is not put on show, and this page says so instead of showing "0".
 */
@HiltViewModel
class GroupNumbersViewModel @Inject constructor(private val group: GroupRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(GroupNumbersUiState())
  val ui: StateFlow<GroupNumbersUiState> = _ui.asStateFlow()

  init { load() }

  fun load() {
    viewModelScope.launch {
      val r = group.numbers()
      _ui.update { st ->
        when (r) {
          is ApiResult.Failure -> st.copy(numbers = Loadable.Failed(r.error))
          // The field follows the server, unless somebody is in the middle of typing something else.
          is ApiResult.Success -> st.copy(numbers = Loadable.Loaded(r.value), typed = if (st.typed.isEmpty() || !st.changed) r.value?.before?.toString().orEmpty() else st.typed)
        }
      }
    }
  }

  fun onTyped(v: String) = _ui.update { it.copy(typed = v.filter(Char::isDigit).take(6), error = null, saved = false) }

  fun save() {
    val count = _ui.value.parsed ?: return
    if (!_ui.value.canSave) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = group.setLettersSentBefore(count)) {
        is ApiResult.Success -> { _ui.update { it.copy(busy = false, saved = true) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_generic)) }
      }
    }
  }
}

@Composable
fun GroupNumbersScreen(onBack: () -> Unit, viewModel: GroupNumbersViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  DetailScaffold(title = stringResource(R.string.title_group_numbers), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val n = ui.numbers) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(n.error, onRetry = viewModel::load)
        is Loadable.Loaded -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val numbers = n.value
          if (numbers == null) { Text(stringResource(R.string.group_numbers_not_yet), style = MaterialTheme.typography.bodyLarge); return@Column }
          Text(stringResource(R.string.group_numbers_intro, numbers.groupName), style = MaterialTheme.typography.bodyLarge)

          SectionTitle(stringResource(R.string.group_numbers_public_heading))
          if (numbers.published == null) Text(stringResource(R.string.group_numbers_not_shown, numbers.total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          KeyValue(stringResource(R.string.label_letters_mailed), numbers.published)
          KeyValue(stringResource(R.string.label_usual_time_to_mail), numbers.averageDaysToMail?.let { pluralStringResource(R.plurals.days_count, it, it) })

          SectionTitle(stringResource(R.string.group_numbers_counted_heading))
          KeyValue(stringResource(R.string.label_marked_mailed_here), numbers.countedHere.toString())
          OutlinedTextField(
            ui.typed, viewModel::onTyped, label = { Text(stringResource(R.string.label_letters_sent_before)) },
            supportingText = { Text(stringResource(R.string.help_letters_sent_before)) },
            singleLine = true, enabled = !ui.busy, isError = ui.typed.isNotEmpty() && ui.parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("letters-before"),
          )
          ui.error?.let { ErrorText(it) }
          if (ui.saved) Text(stringResource(R.string.notice_saved), color = MaterialTheme.colorScheme.onSurfaceVariant)
          Button(onClick = viewModel::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth().testTag("numbers-save")) { Text(stringResource(if (ui.busy) R.string.action_saving else R.string.action_save)) }
        }
      }
    }
  }
}

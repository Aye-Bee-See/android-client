package me.paxana.abcmailbox.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.ReferenceRepository
import me.paxana.abcmailbox.domain.ReferenceLookup
import me.paxana.abcmailbox.domain.ReplyReference
import me.paxana.abcmailbox.domain.WriterMatch
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.longDate
import javax.inject.Inject

data class RecordReplyUiState(
  val number: String = "",
  val busy: Boolean = false,
  val error: String? = null,
  /** The number was refused for good by the server: a typo (checksum) or not this group's (unknown). */
  val numberRefused: Boolean = false,
  val found: ReferenceLookup? = null,
  val name: String = "",
  val searching: Boolean = false,
  val matches: List<WriterMatch>? = null,
  val searchError: String? = null,
) {
  val canLookUp: Boolean get() = !busy && number.isNotBlank()
}

/**
 * A reply came in the post (API PR #120). The number the prisoner copied from the letter's footer is typed first:
 * its shape is checked here, then the server says whose letter it was and where the reply belongs. A reply with no
 * number is filed by the writer's name, searched over every name they have used.
 */
@HiltViewModel
class RecordReplyViewModel @Inject constructor(private val refs: ReferenceRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(RecordReplyUiState())
  val ui: StateFlow<RecordReplyUiState> = _ui.asStateFlow()
  private var search: Job? = null

  fun onNumber(v: String) = _ui.update { it.copy(number = v, error = null, numberRefused = false, found = null) }

  fun lookUp() {
    val typed = _ui.value.number
    ReplyReference.problem(typed, strings)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
    _ui.update { it.copy(busy = true, error = null, numberRefused = false) }
    viewModelScope.launch {
      when (val r = refs.lookup(typed)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, found = r.value, number = ReplyReference.pretty(typed)) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, numberRefused = r.error.isRefusedNumber, error = r.error.toReferenceMessage(strings)) }
      }
    }
  }

  fun onName(v: String) {
    search?.cancel()
    _ui.update { it.copy(name = v, searchError = null, matches = null, searching = false) }
    if (v.trim().length < 2) return
    search = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MS)
      _ui.update { it.copy(searching = true) }
      val r = refs.writers(v)
      _ui.update { s ->
        if (s.name != v) s else when (r) {
          is ApiResult.Success -> s.copy(searching = false, matches = r.value)
          is ApiResult.Failure -> s.copy(searching = false, searchError = r.error.userMessage ?: strings.get(R.string.error_generic))
        }
      }
    }
  }

  companion object { const val SEARCH_DEBOUNCE_MS = 400L }
}

private val AppError.isRefusedNumber: Boolean
  get() = (this is AppError.Validation && condition == "checksum") || (this is AppError.NotFound && condition == "unknown")

internal fun AppError.toReferenceMessage(strings: Strings): String = when {
  this is AppError.Validation && condition == "checksum" -> strings.get(R.string.error_reference_checksum)
  this is AppError.NotFound -> strings.get(R.string.error_reference_unknown)
  this is AppError.Network -> strings.get(R.string.error_network)
  else -> userMessage ?: strings.get(R.string.error_generic)
}

@Composable
fun RecordReplyScreen(
  onBack: () -> Unit,
  /** The reply is recorded on this writer's thread with this prisoner, filed by the number. */
  onRecord: (prisonerId: Int, writerId: Int, reference: String) -> Unit,
  onThread: (chatId: Int) -> Unit,
  /** No number: the writer found by name; who wrote back is chosen next. */
  onWriter: (writerId: Int, name: String) -> Unit,
  viewModel: RecordReplyViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  DetailScaffold(title = stringResource(R.string.title_reply_arrived), onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.reply_lookup_intro), style = MaterialTheme.typography.bodyLarge)
      OutlinedTextField(
        ui.number, viewModel::onNumber, label = { Text(stringResource(R.string.label_reply_reference)) }, placeholder = { Text("0000-0000-0") },
        supportingText = { Text(stringResource(R.string.help_reply_reference)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { viewModel.lookUp() }),
        modifier = Modifier.fillMaxWidth().testTag("reference"),
      )
      if (ui.numberRefused) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
      val found = ui.found
      if (found == null) {
        Button(onClick = viewModel::lookUp, enabled = ui.canLookUp, modifier = Modifier.fillMaxWidth().testTag("reference-look-up")) { Text(stringResource(if (ui.busy) R.string.action_checking else R.string.action_look_up)) }
      } else {
        Card(Modifier.fillMaxWidth().testTag("reference-found")) {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.letter_reference, found.reference), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              if (found.writer.anonymous || found.writer.displayName == null) stringResource(R.string.reference_found_anonymous) else stringResource(R.string.reference_found_from, found.writer.displayName!!),
              style = MaterialTheme.typography.titleMedium,
            )
            found.prisoner?.let { Text(stringResource(R.string.reference_found_to, it.name), style = MaterialTheme.typography.bodyMedium) }
            found.mailedAt?.let { Text(stringResource(R.string.reference_found_mailed, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (found.letter == null) Text(stringResource(R.string.reference_letter_gone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        val prisoner = found.prisoner
        if (prisoner != null) Button(onClick = { onRecord(prisoner.id, found.writer.id, found.reference) }, modifier = Modifier.fillMaxWidth().testTag("record-by-reference")) { Text(stringResource(R.string.action_record_this_reply)) }
        else OutlinedButton(onClick = { onWriter(found.writer.id, found.writer.displayName.orEmpty()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_record_this_reply)) }
        found.chatId?.let { chat -> OutlinedButton(onClick = { onThread(chat) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_conversation)) } }
      }

      HorizontalDivider()
      SectionTitle(stringResource(R.string.reply_no_number))
      Text(stringResource(R.string.reply_by_name_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(ui.name, viewModel::onName, label = { Text(stringResource(R.string.label_writer_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("writer-search"))
      ui.searchError?.let { ErrorText(it) }
      if (ui.searching) Text(stringResource(R.string.action_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
      ui.matches?.let { matches ->
        if (matches.isEmpty()) Text(stringResource(R.string.writers_none_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        matches.forEach { m ->
          val label = if (m.matchedName != null && !m.matchedIsCurrent) stringResource(R.string.writer_formerly, m.displayName, m.matchedName) else m.displayName
          Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().clickable { onWriter(m.id, m.displayName) }.padding(vertical = 10.dp).testTag("writer-${m.id}"))
          HorizontalDivider()
        }
      }
    }
  }
}

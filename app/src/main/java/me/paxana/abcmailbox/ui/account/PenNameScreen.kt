package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.PenNames
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.ui.auth.PenNameChecker
import me.paxana.abcmailbox.ui.auth.PenNameField
import me.paxana.abcmailbox.ui.auth.PenNameState
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.Tag
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable
import javax.inject.Inject

data class PenNameUiState(
  val names: Loadable<PenNames> = Loadable.Loading,
  val typed: PenNameState = PenNameState(),
  val busy: Boolean = false,
  val error: String? = null,
  val saved: String? = null,
) {
  val canSave: Boolean get() = !busy && typed.value.isNotBlank() && !typed.blocks && typed.check?.available == true
}

/** The account's pen name (API PR #120): the current one, a new one checked as it is typed, and every name used before. */
@HiltViewModel
class PenNameViewModel @Inject constructor(private val repo: PenNameRepository, private val sessions: SessionRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(PenNameUiState())
  val ui: StateFlow<PenNameUiState> = _ui.asStateFlow()
  private val checker = PenNameChecker(viewModelScope, repo, strings)

  /** The name the account goes by when it has no pen name: what the letters are signed with today. */
  val displayName: String get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.displayName.orEmpty()

  init {
    viewModelScope.launch { checker.state.collect { st -> _ui.update { it.copy(typed = st) } } }
    load()
  }

  fun load() {
    viewModelScope.launch {
      val r = repo.names()
      _ui.update { it.copy(names = when (r) { is ApiResult.Failure -> Loadable.Failed(r.error); is ApiResult.Success -> Loadable.Loaded(r.value) }) }
    }
  }

  fun onChange(v: String) { checker.onChange(v); _ui.update { it.copy(error = null, saved = null) } }

  fun save() {
    val s = _ui.value
    if (!s.canSave) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.set(s.typed.value)) {
        is ApiResult.Success -> { checker.onChange(""); _ui.update { it.copy(busy = false, saved = r.value) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_generic)) }
      }
    }
  }
}

@Composable
fun PenNameScreen(onBack: () -> Unit, viewModel: PenNameViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val strings = rememberStrings()
  DetailScaffold(title = stringResource(R.string.title_pen_name), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val n = ui.names) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(n.error, onRetry = viewModel::load)
        is Loadable.Loaded -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val names = n.value
          Text(stringResource(R.string.pen_name_intro), style = MaterialTheme.typography.bodyLarge)
          Text(
            names.current?.let { stringResource(R.string.pen_name_current, it) } ?: stringResource(R.string.pen_name_none_yet, viewModel.displayName),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pen-name-current"),
          )
          PenNameField(ui.typed, viewModel::onChange, enabled = !ui.busy, strings = strings, label = stringResource(R.string.label_new_pen_name))
          ui.error?.let { ErrorText(it) }
          ui.saved?.let { Text(stringResource(R.string.notice_pen_name_saved, it), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("pen-name-saved")) }
          Button(onClick = viewModel::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth().testTag("pen-name-save")) { Text(stringResource(if (ui.busy) R.string.action_saving else R.string.action_save)) }

          if (names.names.isNotEmpty()) {
            SectionTitle(stringResource(R.string.section_former_names))
            names.names.forEach { row ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.since?.let { stringResource(R.string.pen_name_since, row.name, it.longDate()) } ?: row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (row.current) Tag(stringResource(R.string.pen_name_current_tag))
              }
            }
          }
        }
      }
    }
  }
}

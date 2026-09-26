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
import androidx.compose.ui.res.pluralStringResource
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
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.repo.penNameLimit
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.PenNames
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.ui.auth.PenNameChecker
import me.paxana.abcmailbox.ui.auth.PenNameField
import me.paxana.abcmailbox.ui.auth.PenNameState
import me.paxana.abcmailbox.ui.common.AlertBanner
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
  private val loaded: PenNames? get() = (names as? Loadable.Loaded)?.value

  /** The limits allow a change today (API #127); until then the screen says when, and offers no field. */
  val canChange: Boolean get() = loaded?.canChange() ?: false

  /** Out of new names this year, and the name typed is not one of the account's own: there is nothing to send. */
  val needsOldName: Boolean get() = loaded?.let { it.newNamesLeft < 1 && typed.value.isNotBlank() && !it.isOwnOldName(typed.value) } ?: false

  /**
   * A name of the right shape that the server said is free, or that it could not be asked about: then the save itself
   * is the check. Not while the check is still coming, not the current name again, and only what the limits allow.
   */
  val canSave: Boolean get() = !busy && canChange && !needsOldName && typed.value.isNotBlank() && loaded?.isCurrent(typed.value) != true &&
    !typed.blocks && (typed.check?.available == true || typed.checkFailed)
}

/**
 * The account's pen name (API PR #120): the current one, a new one checked as it is typed, and every name used before.
 * Since API #127 a change is limited, and the limits are read when the screen opens and said before anyone types.
 */
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

  fun load() { viewModelScope.launch { reload() } }

  private suspend fun reload() {
    val r = repo.names()
    _ui.update { it.copy(names = when (r) { is ApiResult.Failure -> Loadable.Failed(r.error); is ApiResult.Success -> Loadable.Loaded(r.value) }) }
  }

  fun onChange(v: String) { checker.onChange(v); _ui.update { it.copy(error = null, saved = null) } }

  fun save() {
    val s = _ui.value
    if (!s.canSave) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.set(s.typed.value)) {
        is ApiResult.Success -> { checker.onChange(""); _ui.update { it.copy(busy = false, saved = r.value) }; load() }
        is ApiResult.Failure -> {
          val limit = r.error.penNameLimit
          // The limits may have moved since the screen opened (a change from another device): read them again, then say why.
          if (limit != null) reload()
          val message = when {
            limit != null -> refusal(limit)
            r.error is AppError.Network -> strings.get(R.string.pen_name_error_network)
            else -> r.error.userMessage ?: strings.get(R.string.error_generic)
          }
          _ui.update { it.copy(busy = false, error = message) }
        }
      }
    }
  }

  /** The refusal worded on its `condition`, with the dates the limits give, never the server's sentence. */
  private fun refusal(limit: String): String {
    val names = (_ui.value.names as? Loadable.Loaded)?.value
    return if (limit == "new_names") {
      names?.newNamesWindowEnds?.let { strings.get(R.string.pen_name_refused_new_names_until, it.longDate()) } ?: strings.get(R.string.pen_name_refused_new_names)
    } else {
      names?.changeAllowedAt?.let { strings.get(R.string.pen_name_refused_cooldown, it.longDate()) } ?: strings.get(R.string.pen_name_refused_cooldown_later)
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
          ui.saved?.let { Text(stringResource(R.string.notice_pen_name_saved, it), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("pen-name-saved")) }
          PenNameLimits(names)
          if (ui.canChange) {
            PenNameField(ui.typed, viewModel::onChange, enabled = !ui.busy, strings = strings, label = stringResource(if (names.current == null) R.string.label_pen_name else R.string.label_new_pen_name))
            if (ui.needsOldName) ErrorText(stringResource(R.string.pen_name_only_old))
            ui.error?.let { ErrorText(it) }
            Button(onClick = viewModel::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth().testTag("pen-name-save")) { Text(stringResource(if (ui.busy) R.string.action_saving else R.string.action_save)) }
          } else {
            ui.error?.let { ErrorText(it) }
          }

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

/** Said before anyone types (API #127): when a change is next possible, and how many new names are left this year. */
@Composable
private fun PenNameLimits(names: PenNames) {
  val allowed = names.changeAllowedAt
  when {
    allowed != null && !names.canChange() ->
      AlertBanner(pluralStringResource(R.plurals.pen_name_wait_banner, names.cooldownDays, allowed.longDate(), names.cooldownDays), modifier = Modifier.testTag("pen-name-wait"))
    names.current == null -> Muted(stringResource(R.string.pen_name_first_is_free))
    else -> {
      val left = when {
        names.newNamesLeft > 0 -> pluralStringResource(R.plurals.pen_name_new_left, names.newNamesLeft, names.newNamesLeft)
        else -> names.newNamesWindowEnds?.let { stringResource(R.string.pen_name_none_left_until, it.longDate()) } ?: stringResource(R.string.pen_name_none_left)
      }
      val wait = if (names.cooldownDays > 0) " " + pluralStringResource(R.plurals.pen_name_after_change_wait, names.cooldownDays, names.cooldownDays) else ""
      Muted(left + wait, modifier = Modifier.testTag("pen-name-left"))
    }
  }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

package me.paxana.abcmailbox.ui.account

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.paxana.abcmailbox.ui.common.copySecret
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.message
import me.paxana.abcmailbox.data.repo.TwoFactorRepository
import me.paxana.abcmailbox.data.repo.TwoFactorSetup
import me.paxana.abcmailbox.data.repo.TwoFactorStatus
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.QrCode
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable
import javax.inject.Inject

/** Something asked for with a code from the authenticator app: a new set of recovery codes, or switching it off. */
enum class TwoFactorAction { NEW_CODES, SWITCH_OFF }

data class TwoFactorUiState(
  val status: Loadable<TwoFactorStatus> = Loadable.Loading,
  /** Setting up: the secret for the app, and the field for its first code. */
  val setup: TwoFactorSetup? = null,
  val code: String = "",
  /** Just made: shown this once, and the screen is not left until the person says they saved them. */
  val recoveryCodes: List<String>? = null,
  val codesSaved: Boolean = false,
  val asking: TwoFactorAction? = null,
  val busy: Boolean = false,
  val error: String? = null,
  val notice: String? = null,
) {
  val canConfirm: Boolean get() = !busy && code.count(Char::isDigit) == 6
  /** Required and not on yet: nothing else works on the server, so the screen is not left but by signing out. */
  val mustSetUp: Boolean get() = (status as? Loadable.Loaded)?.value?.let { it.required && !it.enabled } == true
}

/**
 * Two-factor sign-in, the account's own settings (API #173, #175), for writers and group admins alike: an authenticator
 * app's six-digit code at each sign-in, with ten recovery codes for a lost phone.
 */
@HiltViewModel
class TwoFactorViewModel @Inject constructor(private val repo: TwoFactorRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(TwoFactorUiState())
  val ui: StateFlow<TwoFactorUiState> = _ui.asStateFlow()

  init { load() }

  fun load() {
    viewModelScope.launch {
      val r = repo.status()
      _ui.update { it.copy(status = when (r) { is ApiResult.Success -> Loadable.Loaded(r.value); is ApiResult.Failure -> Loadable.Failed(r.error) }) }
    }
  }

  fun onCode(v: String) = _ui.update { it.copy(code = v.filter(Char::isDigit).take(6), error = null) }

  fun startSetup() {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.setup()) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, setup = r.value, code = "") }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.message(strings) ?: strings.get(R.string.error_generic)) }
      }
    }
  }

  fun cancelSetup() = _ui.update { it.copy(setup = null, code = "", error = null) }

  fun confirm() {
    val s = _ui.value
    if (!s.canConfirm) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.confirm(s.code)) {
        is ApiResult.Success -> { _ui.update { it.copy(busy = false, setup = null, code = "", recoveryCodes = r.value, codesSaved = false) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, code = "", error = r.error.codeMessage(strings)) }
      }
    }
  }

  fun onCodesSaved(v: Boolean) = _ui.update { it.copy(codesSaved = v) }
  fun doneWithCodes() { if (_ui.value.codesSaved) _ui.update { it.copy(recoveryCodes = null, codesSaved = false) } }

  fun ask(action: TwoFactorAction?) = _ui.update { it.copy(asking = action, error = null) }

  /** A new set of recovery codes, with a code from the app: the old set stops working. */
  fun newCodes(code: String) {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.newRecoveryCodes(code)) {
        is ApiResult.Success -> { _ui.update { it.copy(busy = false, asking = null, recoveryCodes = r.value, codesSaved = false) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.codeMessage(strings)) }
      }
    }
  }

  /** Off, with a code from the app or a recovery code. Refused while it is required (`409 required`). */
  fun switchOff(code: String, recovery: Boolean) {
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = repo.switchOff(code, recovery)) {
        is ApiResult.Success -> { _ui.update { it.copy(busy = false, asking = null, notice = strings.get(R.string.notice_two_factor_off)) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = if ((r.error as? AppError.Conflict)?.condition == "required") strings.get(R.string.two_factor_required_cannot_off) else r.error.codeMessage(strings, recovery)) }
      }
    }
  }

  fun noticeShown() = _ui.update { it.copy(notice = null) }
}

/** A code refused as not right (`400 not_eligible`) is said so in the reader's words; anything else as it came. */
private fun AppError.codeMessage(strings: Strings, recovery: Boolean = false): String = when (this) {
  is AppError.Validation -> strings.get(if (recovery) R.string.two_factor_wrong_recovery_code else R.string.two_factor_wrong_code)
  is AppError.Network -> strings.get(R.string.error_network)
  else -> message(strings) ?: strings.get(R.string.error_generic)
}

/**
 * [setUpFirst]: the shell knows this account must set two-factor up before anything else works (API #175). The screen
 * learns the same from its own status, but only once that has loaded; this covers the time it has not, or cannot.
 * [onCodesOnShow]: recovery codes are on the screen (true) or no longer (false). Back is held here until they are
 * saved, but the shell's bottom bar is not this screen's to hold, so the shell is told and takes its tabs away.
 */
@Composable
fun TwoFactorScreen(onBack: () -> Unit, onSignOut: () -> Unit, setUpFirst: Boolean = false, onCodesOnShow: (Boolean) -> Unit = {}, viewModel: TwoFactorViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val codesOnShow = ui.recoveryCodes != null
  androidx.compose.runtime.DisposableEffect(codesOnShow) { onCodesOnShow(codesOnShow); onDispose { onCodesOnShow(false) } }
  val snackbar = remember { SnackbarHostState() }
  var leaveWithoutSaving by remember { mutableStateOf(false) }
  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  // Recovery codes are shown once: leaving before they are saved is asked about. Required and not set up: not left at all.
  val mustSetUp = ui.mustSetUp || (setUpFirst && ui.status !is Loadable.Loaded)
  val holdBack = (ui.recoveryCodes != null && !ui.codesSaved) || mustSetUp
  val back = { if (ui.recoveryCodes != null && !ui.codesSaved) leaveWithoutSaving = true else if (!mustSetUp) onBack() }
  BackHandler(enabled = holdBack) { back() }

  DetailScaffold(title = stringResource(R.string.title_two_factor), onBack = back) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      val codes = ui.recoveryCodes
      // Recovery codes first, whatever the status says: the server says them once, and the status asked for right
      // after may fail (no signal). Behind an error they were out of reach while Back still insisted they be saved.
      if (codes != null) Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        RecoveryCodes(codes, ui.codesSaved, viewModel::onCodesSaved, viewModel::doneWithCodes)
      } else when (val s = ui.status) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          ErrorBox(s.error, onRetry = viewModel::load)
          // Required and unreachable: every other screen sends the person back here, so the way out has to be here too.
          if (mustSetUp) TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) }
        }
        is Loadable.Loaded -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val status = s.value
          if (status.required && !status.enabled) AlertBanner(stringResource(R.string.two_factor_required_banner, becauseWords(status.requiredBecause)), modifier = Modifier.testTag("two-factor-required"))
          val setup = ui.setup
          when {
            setup != null -> SetUp(setup, ui, viewModel)
            status.enabled -> Enabled(status, ui.busy, onNewCodes = { viewModel.ask(TwoFactorAction.NEW_CODES) }, onSwitchOff = { viewModel.ask(TwoFactorAction.SWITCH_OFF) })
            else -> {
              Text(stringResource(R.string.two_factor_intro), style = MaterialTheme.typography.bodyLarge)
              Text(stringResource(R.string.two_factor_intro_apps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              ui.error?.let { ErrorText(it) }
              Button(onClick = viewModel::startSetup, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("two-factor-start")) { Text(stringResource(R.string.action_set_up_two_factor)) }
            }
          }
          if (ui.mustSetUp) TextButton(onClick = onSignOut) { Text(stringResource(R.string.action_sign_out)) }
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
  }

  ui.asking?.let { action -> CodeDialog(action, ui.busy, ui.error, onDismiss = { viewModel.ask(null) }, onSubmit = { code, recovery -> if (action == TwoFactorAction.NEW_CODES) viewModel.newCodes(code) else viewModel.switchOff(code, recovery) }) }
  if (leaveWithoutSaving) AlertDialog(
    onDismissRequest = { leaveWithoutSaving = false },
    title = { Text(stringResource(R.string.two_factor_codes_unsaved_title)) },
    text = { Text(stringResource(R.string.two_factor_codes_unsaved_text)) },
    confirmButton = { TextButton(onClick = { leaveWithoutSaving = false }) { Text(stringResource(R.string.action_stay)) } },
  )
}

/** Why it is required, in the reader's words: the API's `superadmins`, `all_groups`, `group`. */
@Composable
private fun becauseWords(because: List<String>): String = stringResource(when {
  "group" in because -> R.string.two_factor_because_group
  "all_groups" in because -> R.string.two_factor_because_all_groups
  "superadmins" in because -> R.string.two_factor_because_superadmins
  else -> R.string.two_factor_because_site
})

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SetUp(setup: TwoFactorSetup, ui: TwoFactorUiState, viewModel: TwoFactorViewModel) {
  val context = LocalContext.current
  var noApp by remember { mutableStateOf(false) }
  Text(stringResource(R.string.two_factor_step_add), style = MaterialTheme.typography.titleMedium)
  Text(stringResource(R.string.two_factor_step_add_text), style = MaterialTheme.typography.bodyMedium)
  // An authenticator on this very phone takes the otpauth:// link directly; a camera cannot read this screen.
  OutlinedButton(onClick = {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setup.otpauthUri))) } catch (e: ActivityNotFoundException) { noApp = true }
  }, modifier = Modifier.fillMaxWidth().testTag("two-factor-open-app")) { Text(stringResource(R.string.action_open_authenticator)) }
  if (noApp) Text(stringResource(R.string.two_factor_no_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  Text(stringResource(R.string.two_factor_or_scan), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  QrCode(setup.otpauthUri, modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally), contentDescription = stringResource(R.string.two_factor_qr_description))
  Text(stringResource(R.string.two_factor_or_type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  SelectionContainer { Text(setup.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("two-factor-secret")) }
  TextButton(onClick = { context.copySecret("authenticator key", setup.secret) }) { Text(stringResource(R.string.action_copy_key)) }

  Text(stringResource(R.string.two_factor_step_confirm), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
  OutlinedTextField(
    ui.code, viewModel::onCode, singleLine = true, enabled = !ui.busy, label = { Text(stringResource(R.string.label_two_factor_code)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag("two-factor-confirm-code"),
  )
  ui.error?.let { ErrorText(it) }
  Button(onClick = viewModel::confirm, enabled = ui.canConfirm, modifier = Modifier.fillMaxWidth().testTag("two-factor-confirm")) { Text(stringResource(R.string.action_switch_on)) }
  if (!ui.mustSetUp) TextButton(onClick = viewModel::cancelSetup, enabled = !ui.busy) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun RecoveryCodes(codes: List<String>, saved: Boolean, onSaved: (Boolean) -> Unit, onDone: () -> Unit) {
  val context = LocalContext.current
  Text(stringResource(R.string.two_factor_codes_title), style = MaterialTheme.typography.titleMedium)
  Text(stringResource(R.string.two_factor_codes_text), style = MaterialTheme.typography.bodyMedium)
  SelectionContainer {
    Column(Modifier.testTag("two-factor-codes")) { codes.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) } }
  }
  OutlinedButton(onClick = { context.copySecret("recovery codes", codes.joinToString("\n")) }) { Text(stringResource(R.string.action_copy_all)) }
  Row(
    Modifier.fillMaxWidth().toggleable(value = saved, role = Role.Checkbox, onValueChange = onSaved).testTag("two-factor-codes-saved"),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Checkbox(checked = saved, onCheckedChange = null)
    Text(stringResource(R.string.two_factor_codes_saved), style = MaterialTheme.typography.bodyMedium)
  }
  Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth().testTag("two-factor-codes-done")) { Text(stringResource(R.string.action_done)) }
}

@Composable
private fun Enabled(status: TwoFactorStatus, busy: Boolean, onNewCodes: () -> Unit, onSwitchOff: () -> Unit) {
  Text(status.enabledAt?.let { stringResource(R.string.two_factor_on_since, it.longDate()) } ?: stringResource(R.string.two_factor_on), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("two-factor-on"))
  Text(pluralStringResource(R.plurals.two_factor_codes_left, status.recoveryCodesLeft, status.recoveryCodesLeft), style = MaterialTheme.typography.bodyMedium,
    color = if (status.recoveryCodesLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
  OutlinedButton(onClick = onNewCodes, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_new_recovery_codes)) }
  // While it is required it cannot be switched off (API #175), so the button is not offered.
  if (status.required) Text(stringResource(R.string.two_factor_required_cannot_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  else TextButton(onClick = onSwitchOff, enabled = !busy, modifier = Modifier.testTag("two-factor-off")) { Text(stringResource(R.string.action_switch_off), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CodeDialog(action: TwoFactorAction, busy: Boolean, error: String?, onDismiss: () -> Unit, onSubmit: (String, Boolean) -> Unit) {
  var code by remember { mutableStateOf("") }
  var recovery by remember { mutableStateOf(false) }
  val ready = if (recovery) code.count(Char::isLetterOrDigit) >= 10 else code.count(Char::isDigit) == 6
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(if (action == TwoFactorAction.NEW_CODES) R.string.two_factor_new_codes_title else R.string.two_factor_off_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (action == TwoFactorAction.NEW_CODES) R.string.two_factor_new_codes_text else R.string.two_factor_off_text), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          code, { code = if (recovery) it.take(24) else it.filter(Char::isDigit).take(6) }, singleLine = true, enabled = !busy,
          label = { Text(stringResource(if (recovery) R.string.label_recovery_code_2fa else R.string.label_two_factor_code)) },
          keyboardOptions = if (recovery) KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false) else KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          modifier = Modifier.fillMaxWidth().testTag("two-factor-dialog-code"),
        )
        error?.let { ErrorText(it) }
        // Only switching off takes a recovery code: a lost phone is exactly when someone needs to.
        if (action == TwoFactorAction.SWITCH_OFF) TextButton(onClick = { recovery = !recovery; code = "" }, enabled = !busy) {
          Text(stringResource(if (recovery) R.string.action_use_app_code else R.string.action_use_recovery_code))
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSubmit(code, recovery) }, enabled = ready && !busy, modifier = Modifier.testTag("two-factor-dialog-confirm")) {
        Text(stringResource(if (action == TwoFactorAction.NEW_CODES) R.string.action_make_new_codes else R.string.action_switch_off))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

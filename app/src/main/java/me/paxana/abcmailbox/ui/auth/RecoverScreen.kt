package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.ErrorText
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import me.paxana.abcmailbox.crypto.SecretCodes
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.UppercaseTransformation
import javax.inject.Inject

data class RecoverUiState(
  val mode: EncryptionMode = EncryptionMode.UNKNOWN,
  val username: String = "",
  val code: String = "",
  val password: String = "",
  val confirm: String = "",
  val show: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
) {
  val matches: Boolean get() = password == confirm
  val canSubmit: Boolean get() = !busy && username.isNotBlank() && code.isNotBlank() && password.length >= 7 && matches
}

@HiltViewModel
class RecoverViewModel @Inject constructor(
  private val sessions: SessionRepository,
  private val modes: EncryptionModeRepository,
  private val strings: Strings,
) : ViewModel() {
  private val _ui = MutableStateFlow(RecoverUiState(mode = modes.mode.value))
  val ui: StateFlow<RecoverUiState> = _ui.asStateFlow()

  init { viewModelScope.launch { val m = modes.current(); _ui.update { it.copy(mode = m) } } }

  fun onUsername(v: String) = _ui.update { it.copy(username = v, error = null) }
  fun onCode(v: String) = _ui.update { it.copy(code = v, error = null) }
  fun onPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onConfirm(v: String) = _ui.update { it.copy(confirm = v, error = null) }
  fun onToggleShow() = _ui.update { it.copy(show = !it.show) }

  fun submit() {
    val s = _ui.value
    if (!s.canSubmit) return
    // Check the code's shape locally: recovery starts are rate limited per username.
    if (!SecretCodes.isWellFormed(s.code)) { _ui.update { it.copy(error = strings.get(R.string.error_recovery_code_format)) }; return }
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.recover(s.username, s.code, s.password)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "", code = "") }
        is ApiResult.Failure -> _ui.update {
          it.copy(busy = false, error = when (val e = r.error) {
            is AppError.NotFound -> strings.get(R.string.error_recover_no_account)
            is AppError.Unauthorized -> strings.get(R.string.error_recover_refused)
            is AppError.Network -> strings.get(R.string.error_recover_network)
            else -> e.userMessage ?: strings.get(R.string.error_recover_failed)
          })
        }
      }
    }
  }
}

/**
 * After `forgot-password.html`, corrected for what the API offers. On an
 * end-to-end server the recovery code works: it unwraps the private key, the
 * app proves possession to the server, and a new password is set. In server
 * mode there is no self-service path and the page explains the real options.
 */
@Composable
fun RecoverScreen(sessionState: SessionState, onBack: () -> Unit, onClaim: () -> Unit, onRecovered: () -> Unit, viewModel: RecoverViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(sessionState) { if (sessionState is SessionState.SignedIn) onRecovered() }

  DetailScaffold(title = stringResource(R.string.action_forgot_password), onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.recover_no_reset_link), style = MaterialTheme.typography.bodyLarge)

      if (ui.mode == EncryptionMode.E2E) {
        SectionTitle(stringResource(R.string.recover_section_code))
        Text(stringResource(R.string.recover_code_explained), style = MaterialTheme.typography.bodyMedium)
        val transform = if (ui.show) VisualTransformation.None else PasswordVisualTransformation()
        OutlinedTextField(ui.username, viewModel::onUsername, label = { Text(stringResource(R.string.label_username)) }, singleLine = true, enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("recover-username"))
        OutlinedTextField(ui.code, viewModel::onCode, label = { Text(stringResource(R.string.label_recovery_code)) }, singleLine = true, enabled = !ui.busy,
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
          modifier = Modifier.fillMaxWidth().testTag("recover-code"))
        OutlinedTextField(ui.password, viewModel::onPassword, label = { Text(stringResource(R.string.label_new_password)) }, supportingText = { Text(stringResource(R.string.help_password_length)) }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
          trailingIcon = { TextButton(onClick = viewModel::onToggleShow) { Text(stringResource(if (ui.show) R.string.action_hide else R.string.action_show)) } },
          modifier = Modifier.fillMaxWidth().testTag("recover-password"))
        OutlinedTextField(ui.confirm, viewModel::onConfirm, label = { Text(stringResource(R.string.label_confirm_new_password)) }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
          isError = ui.confirm.isNotEmpty() && !ui.matches,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("recover-confirm"))
        ui.error?.let { ErrorText(it) }
        Button(onClick = viewModel::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("recover-submit")) { Text(stringResource(if (ui.busy) R.string.action_recovering else R.string.action_set_new_password)) }
        Text(stringResource(R.string.recover_lost_code_too), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        SectionTitle(stringResource(R.string.recover_section_own))
        Text(stringResource(R.string.recover_own_explained), style = MaterialTheme.typography.bodyMedium)
      }

      SectionTitle(stringResource(R.string.recover_section_unclaimed))
      Text(stringResource(R.string.recover_unclaimed_explained), style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onClaim) { Text(stringResource(R.string.action_have_token)) }
      Text(stringResource(R.string.recover_lockout_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

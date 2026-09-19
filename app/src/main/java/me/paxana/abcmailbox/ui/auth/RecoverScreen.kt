package me.paxana.abcmailbox.ui.auth

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
    if (!SecretCodes.isWellFormed(s.code)) { _ui.update { it.copy(error = "A recovery code has 24 letters and digits, and never I, L, O, or U.") }; return }
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.recover(s.username, s.code, s.password)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "", code = "") }
        is ApiResult.Failure -> _ui.update {
          it.copy(busy = false, error = when (val e = r.error) {
            is AppError.NotFound -> "No account with that username has a recovery code."
            is AppError.Unauthorized -> "Recovery was refused. Start again; each attempt is valid for ten minutes and works once."
            is AppError.Network -> "Can't reach the server. Nothing has changed."
            else -> e.userMessage ?: "Recovery failed. Please try again."
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

  DetailScaffold(title = "Forgot your password?", onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("There is no reset link we can email you. That is deliberate: a server that can reset your password is a server that can get into your letters.", style = MaterialTheme.typography.bodyLarge)

      if (ui.mode == EncryptionMode.E2E) {
        SectionTitle("Use your recovery code")
        Text("Enter the code you saved when you set up your account, and choose a new password. Your letters stay readable: the key does not change, only the password that protects it.", style = MaterialTheme.typography.bodyMedium)
        val transform = if (ui.show) VisualTransformation.None else PasswordVisualTransformation()
        OutlinedTextField(ui.username, viewModel::onUsername, label = { Text("Username") }, singleLine = true, enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("recover-username"))
        OutlinedTextField(ui.code, viewModel::onCode, label = { Text("Recovery code") }, singleLine = true, enabled = !ui.busy,
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
          modifier = Modifier.fillMaxWidth().testTag("recover-code"))
        OutlinedTextField(ui.password, viewModel::onPassword, label = { Text("New password") }, supportingText = { Text("At least 7 characters") }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
          trailingIcon = { TextButton(onClick = viewModel::onToggleShow) { Text(if (ui.show) "Hide" else "Show") } },
          modifier = Modifier.fillMaxWidth().testTag("recover-password"))
        OutlinedTextField(ui.confirm, viewModel::onConfirm, label = { Text("Confirm new password") }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
          isError = ui.confirm.isNotEmpty() && !ui.matches,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("recover-confirm"))
        ui.error?.let { ErrorText(it) }
        Button(onClick = viewModel::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("recover-submit")) { Text(if (ui.busy) "Recovering…" else "Set new password") }
        Text("Lost the code too? Then the letters on this account cannot be recovered by anyone. A network admin can help you start a new account.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        SectionTitle("If the account is your own")
        Text("Contact a network admin through the group you write with. They can confirm who you are and set a new password for you.", style = MaterialTheme.typography.bodyMedium)
      }

      SectionTitle("If a support group set up your account and you have not claimed it yet")
      Text("Ask the group for a new claim token. Tokens last 72 hours and work once; they can make another at any time.", style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onClaim) { Text("I have a claim token") }
      Text("Too many wrong sign-in attempts lock a username for 15 minutes. Waiting is sometimes all it takes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

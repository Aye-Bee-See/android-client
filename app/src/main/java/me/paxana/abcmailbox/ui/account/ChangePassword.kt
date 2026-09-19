package me.paxana.abcmailbox.ui.account

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
import androidx.compose.ui.text.input.ImeAction
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
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.ui.common.DetailScaffold
import javax.inject.Inject

data class ChangePasswordUiState(
  val current: String = "",
  val new: String = "",
  val confirm: String = "",
  val show: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  val done: Boolean = false,
) {
  val matches: Boolean get() = new == confirm
  val canSubmit: Boolean get() = !busy && current.isNotEmpty() && new.length >= 7 && matches && new != current
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(private val sessions: SessionRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(ChangePasswordUiState())
  val ui: StateFlow<ChangePasswordUiState> = _ui.asStateFlow()

  fun onCurrent(v: String) = _ui.update { it.copy(current = v, error = null) }
  fun onNew(v: String) = _ui.update { it.copy(new = v, error = null) }
  fun onConfirm(v: String) = _ui.update { it.copy(confirm = v, error = null) }
  fun onToggleShow() = _ui.update { it.copy(show = !it.show) }

  fun submit() {
    val s = _ui.value
    if (!s.canSubmit) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.changePassword(s.current, s.new)) {
        is ApiResult.Success -> _ui.update { ChangePasswordUiState(done = true) }
        is ApiResult.Failure -> _ui.update {
          it.copy(busy = false, error = when (val e = r.error) {
            is AppError.Network -> strings.get(R.string.error_password_network)
            else -> e.userMessage ?: strings.get(R.string.error_change_password)
          })
        }
      }
    }
  }
}

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(ui.done) { if (ui.done) onDone() }
  val transform = if (ui.show) VisualTransformation.None else PasswordVisualTransformation()

  DetailScaffold(title = stringResource(R.string.action_change_password), onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(stringResource(R.string.change_password_intro), style = MaterialTheme.typography.bodyLarge)
      OutlinedTextField(ui.current, viewModel::onCurrent, label = { Text(stringResource(R.string.label_current_password)) }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShow) { Text(stringResource(if (ui.show) R.string.action_hide else R.string.action_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("pw-current"))
      OutlinedTextField(ui.new, viewModel::onNew, label = { Text(stringResource(R.string.label_new_password)) }, supportingText = { Text(stringResource(R.string.help_new_password)) }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("pw-new"))
      OutlinedTextField(ui.confirm, viewModel::onConfirm, label = { Text(stringResource(R.string.label_confirm_new_password)) }, singleLine = true, enabled = !ui.busy, visualTransformation = transform,
        isError = ui.confirm.isNotEmpty() && !ui.matches,
        supportingText = { if (ui.confirm.isNotEmpty() && !ui.matches) Text(stringResource(R.string.error_passwords_differ)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("pw-confirm"))
      ui.error?.let { ErrorText(it) }
      Button(onClick = viewModel::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("pw-submit")) { Text(stringResource(if (ui.busy) R.string.action_changing else R.string.action_change_password)) }
    }
  }
}

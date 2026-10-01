package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.ErrorText
import javax.inject.Inject

data class TwoFactorCodeUiState(
  val code: String = "",
  /** Typing one of the recovery codes instead: for a phone that is lost. */
  val recovery: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  /** The challenge is used up or out of time: the password has to be given again. */
  val expired: Boolean = false,
) {
  val canSubmit: Boolean get() = !busy && if (recovery) code.count(Char::isLetterOrDigit) >= 10 else code.count(Char::isDigit) == 6
}

/**
 * The second step of a two-factor sign-in (API #173), after the password was accepted: a code from the authenticator
 * app, or a recovery code. A wrong code may be tried again; the sign-in itself lasts five minutes.
 */
@HiltViewModel
class TwoFactorCodeViewModel @Inject constructor(private val sessions: SessionRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(TwoFactorCodeUiState())
  val ui: StateFlow<TwoFactorCodeUiState> = _ui.asStateFlow()

  fun onCode(v: String) = _ui.update { st -> st.copy(code = if (st.recovery) v.take(24) else v.filter(Char::isDigit).take(6), error = null) }
  fun toggleRecovery() = _ui.update { it.copy(recovery = !it.recovery, code = "", error = null) }

  fun submit() {
    val s = _ui.value
    if (!s.canSubmit) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.completeTwoFactor(s.code, s.recovery)) {
        // The session flips to signed in; whichever screen this is on leaves by itself.
        is ApiResult.Success -> _ui.update { it.copy(busy = false, code = "") }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, code = "", expired = r.error is AppError.Unauthorized, error = r.error.toTwoFactorMessage(s.recovery, strings)) }
      }
    }
  }

  /** Back to the password: the half-finished sign-in is forgotten. */
  fun cancel() = sessions.cancelTwoFactor()
}

internal fun AppError.toTwoFactorMessage(recovery: Boolean, strings: Strings): String = when (this) {
  // `not_eligible` on `code` or `recoveryCode`: the challenge is still good for another try.
  is AppError.Validation -> strings.get(if (recovery) R.string.two_factor_wrong_recovery_code else R.string.two_factor_wrong_code)
  is AppError.Unauthorized -> strings.get(R.string.two_factor_expired)
  is AppError.RateLimited -> retryAfterSeconds?.let { strings.plural(R.plurals.error_rate_limited_minutes, ((it + 59) / 60).coerceAtLeast(1).toInt()) } ?: strings.get(R.string.error_too_many_sign_ins)
  is AppError.Network -> strings.get(R.string.error_network)
  else -> message(strings) ?: strings.get(R.string.error_generic)
}

/**
 * Shown in place of a password form once the password was accepted and a code is needed. [onBack] returns to the
 * password, which is also where an expired sign-in goes, with its sentence shown there by [onExpired].
 */
@Composable
fun TwoFactorCodeStep(onBack: () -> Unit, onExpired: (String) -> Unit, modifier: Modifier = Modifier, viewModel: TwoFactorCodeViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(ui.expired) { if (ui.expired) onExpired(ui.error.orEmpty()) }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.two_factor_code_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(if (ui.recovery) R.string.two_factor_code_recovery_explained else R.string.two_factor_code_explained), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
      ui.code, viewModel::onCode, singleLine = true, enabled = !ui.busy,
      label = { Text(stringResource(if (ui.recovery) R.string.label_recovery_code_2fa else R.string.label_two_factor_code)) },
      keyboardOptions = if (ui.recovery) KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done)
        else KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
      modifier = Modifier.fillMaxWidth().testTag("two-factor-code"),
    )
    ui.error?.takeIf { !ui.expired }?.let { ErrorText(it) }
    Button(onClick = viewModel::submit, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("two-factor-submit")) {
      Text(stringResource(if (ui.busy) R.string.action_signing_in_2fa else R.string.action_sign_in))
    }
    TextButton(onClick = viewModel::toggleRecovery, enabled = !ui.busy, modifier = Modifier.testTag("two-factor-use-recovery")) {
      Text(stringResource(if (ui.recovery) R.string.action_use_app_code else R.string.action_use_recovery_code))
    }
    TextButton(onClick = { viewModel.cancel(); onBack() }, enabled = !ui.busy) { Text(stringResource(R.string.action_back_to_password)) }
  }
}

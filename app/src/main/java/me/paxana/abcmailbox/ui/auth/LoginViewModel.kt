package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.lifecycle.ViewModel
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
import javax.inject.Inject

/** Everything the sign-in screen shows, in one immutable value. */
data class LoginUiState(
  val username: String = "",
  val password: String = "",
  val showPassword: Boolean = false,
  val submitting: Boolean = false,
  val error: String? = null,
) {
  val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !submitting
}

/**
 * Unidirectional data flow: the screen renders [uiState] and calls the event
 * functions; the ViewModel is the only thing that changes the state. It
 * survives rotation, so a half-typed username is not lost.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
  private val sessions: SessionRepository,
  private val strings: Strings,
) : ViewModel() {

  private val _uiState = MutableStateFlow(LoginUiState())
  val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

  fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
  fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
  fun onToggleShowPassword() = _uiState.update { it.copy(showPassword = !it.showPassword) }

  fun onSubmit() {
    val current = _uiState.value
    if (!current.canSubmit) return
    _uiState.update { it.copy(submitting = true, error = null) }
    viewModelScope.launch {
      when (val result = sessions.login(current.username, current.password)) {
        is ApiResult.Success -> _uiState.update { it.copy(submitting = false, password = "") }
        is ApiResult.Failure -> _uiState.update { it.copy(submitting = false, error = result.error.toLoginMessage(strings)) }
      }
    }
  }
}

internal fun AppError.toLoginMessage(strings: Strings): String = when (this) {
  is AppError.Unauthorized -> strings.get(R.string.error_wrong_credentials)
  // The server's sentence if it sent one; otherwise the wait it asked for, in the user's language.
  is AppError.RateLimited -> info ?: retryAfterSeconds?.let { strings.plural(R.plurals.error_rate_limited_minutes, ((it + 59) / 60).toInt()) } ?: strings.get(R.string.error_too_many_sign_ins)
  is AppError.Validation -> errors.joinToString(" ")
  is AppError.Network -> strings.get(R.string.error_network)
  else -> userMessage ?: strings.get(R.string.error_generic)
}

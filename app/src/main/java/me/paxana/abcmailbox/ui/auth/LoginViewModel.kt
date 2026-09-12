package me.paxana.abcmailbox.ui.auth

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
        is ApiResult.Failure -> _uiState.update { it.copy(submitting = false, error = result.error.toLoginMessage()) }
      }
    }
  }
}

internal fun AppError.toLoginMessage(): String = when (this) {
  is AppError.Unauthorized -> "Incorrect username or password."
  is AppError.Validation -> errors.joinToString(" ")
  is AppError.Network -> "Can't reach the server. Check your connection and try again."
  else -> userMessage ?: "Something went wrong. Please try again."
}

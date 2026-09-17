package me.paxana.abcmailbox.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.domain.ClaimInfo
import me.paxana.abcmailbox.domain.ClaimToken
import me.paxana.abcmailbox.ui.nav.ClaimRoute
import javax.inject.Inject

data class ClaimUiState(
  val token: String = "",
  val info: ClaimInfo? = null,
  val username: String = "",
  val password: String = "",
  val confirm: String = "",
  val email: String = "",
  val understood: Boolean = false,
  val showPassword: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  /** True when the token was refused as used or expired: show the "ask for a new one" state. */
  val tokenDead: Boolean = false,
) {
  val passwordsMatch: Boolean get() = password == confirm
  val canCheck: Boolean get() = !busy && token.isNotBlank()
  val canClaim: Boolean get() = !busy && info != null && username.trim().length in 3..16 && password.length >= 7 && passwordsMatch && understood
}

/**
 * Two steps, after `claim.html`: check the token (so the page can say who it
 * is for), then choose credentials. The token's format is validated locally
 * first because the API allows only a few checks per hour.
 */
@HiltViewModel
class ClaimViewModel(
  private val sessions: SessionRepository,
  route: ClaimRoute,
) : ViewModel() {

  @Inject
  constructor(sessions: SessionRepository, savedStateHandle: SavedStateHandle) : this(sessions, savedStateHandle.toRoute<ClaimRoute>())

  private val _ui = MutableStateFlow(ClaimUiState(token = route.token?.let { ClaimToken.pretty(it) }.orEmpty()))
  val ui: StateFlow<ClaimUiState> = _ui.asStateFlow()

  init {
    // Arrived by link with a token: check it straight away.
    if (route.token != null && ClaimToken.isWellFormed(route.token)) check()
  }

  fun onTokenChange(v: String) = _ui.update { it.copy(token = v, error = null, tokenDead = false, info = null) }
  fun onUsernameChange(v: String) = _ui.update { it.copy(username = v, error = null) }
  fun onPasswordChange(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onConfirmChange(v: String) = _ui.update { it.copy(confirm = v, error = null) }
  fun onEmailChange(v: String) = _ui.update { it.copy(email = v, error = null) }
  fun onUnderstoodChange(v: Boolean) = _ui.update { it.copy(understood = v) }
  fun onToggleShowPassword() = _ui.update { it.copy(showPassword = !it.showPassword) }
  fun startOver() = _ui.update { ClaimUiState() }

  fun check() {
    val typed = _ui.value.token
    ClaimToken.problem(typed)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
    _ui.update { it.copy(busy = true, error = null, tokenDead = false) }
    viewModelScope.launch {
      when (val r = sessions.claimInfo(ClaimToken.normalise(typed))) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, info = r.value, token = ClaimToken.pretty(typed)) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, tokenDead = r.error is AppError.Gone, error = r.error.toClaimMessage()) }
      }
    }
  }

  fun claim() {
    val s = _ui.value
    if (!s.canClaim) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.claim(ClaimToken.normalise(s.token), s.username, s.password, s.email)) {
        // Success flips the session to signed-in; the screen leaves on its own.
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "") }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, tokenDead = r.error is AppError.Gone, error = r.error.toClaimMessage()) }
      }
    }
  }
}

internal fun AppError.toClaimMessage(): String = when (this) {
  is AppError.NotFound -> "That token is not valid. Check it against what your group gave you."
  is AppError.Gone -> "This token has already been used or has expired. Tokens last 72 hours and work once. Ask the group that set up your account for a new one."
  is AppError.Network -> "Can't reach the server. Check your connection and try again."
  else -> userMessage ?: "Something went wrong. Please try again."
}

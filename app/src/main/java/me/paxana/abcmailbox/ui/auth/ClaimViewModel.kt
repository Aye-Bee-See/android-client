package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.domain.PasswordRules
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
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
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
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
  /** The account was claimed and signed in from this screen: the session that follows is the new one, and the screen may leave. */
  val claimed: Boolean = false,
  /** The pen name as typed and checked (API PR #120); optional, so an empty one never blocks. */
  val penName: PenNameState = PenNameState(),
) {
  val passwordsMatch: Boolean get() = password == confirm
  val canCheck: Boolean get() = !busy && token.isNotBlank()
  val canClaim: Boolean get() = !busy && info != null && username.trim().length in 3..16 && password.length >= PasswordRules.MIN_LENGTH && passwordsMatch && understood && !penName.blocks
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
  private val strings: Strings,
  penNames: PenNameRepository,
) : ViewModel() {

  @Inject
  constructor(sessions: SessionRepository, strings: Strings, penNames: PenNameRepository, savedStateHandle: SavedStateHandle) : this(sessions, savedStateHandle.toRoute<ClaimRoute>(), strings, penNames)

  private val _ui = MutableStateFlow(ClaimUiState(token = route.token?.let { ClaimToken.pretty(it) }.orEmpty()))
  val ui: StateFlow<ClaimUiState> = _ui.asStateFlow()
  private val penName = PenNameChecker(viewModelScope, penNames, strings)

  /** Signed in already: a claim takes over another account, which must not replace the session unasked. */
  private val signedInAs: String? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.username

  init {
    viewModelScope.launch { penName.state.collect { st -> _ui.update { it.copy(penName = st) } } }
    // Arrived by link with a token: check it straight away, unless somebody is signed in (the screen says so instead).
    if (route.token != null && ClaimToken.isWellFormed(route.token) && signedInAs == null) check()
  }

  fun onPenNameChange(v: String) { penName.onChange(v); _ui.update { it.copy(error = null) } }

  fun signOut() { viewModelScope.launch { sessions.logout() } }

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
    signedInAs?.let { name -> _ui.update { it.copy(error = strings.get(R.string.claim_signed_in, name)) }; return }
    ClaimToken.problem(typed, strings)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
    _ui.update { it.copy(busy = true, error = null, tokenDead = false) }
    viewModelScope.launch {
      when (val r = sessions.claimInfo(ClaimToken.normalise(typed))) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, info = r.value, token = ClaimToken.pretty(typed)) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, tokenDead = r.error is AppError.Gone, error = r.error.toClaimMessage(strings)) }
      }
    }
  }

  fun claim() {
    val s = _ui.value
    if (!s.canClaim) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.claim(ClaimToken.normalise(s.token), s.username, s.password, s.email, s.penName.value.ifBlank { null })) {
        // Success flips the session to signed-in; the screen leaves on its own.
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "", claimed = true) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, tokenDead = r.error is AppError.Gone, error = r.error.toClaimMessage(strings)) }
      }
    }
  }
}

internal fun AppError.toClaimMessage(strings: Strings): String = when (this) {
  is AppError.NotFound -> strings.get(R.string.error_token_invalid)
  // No lifetime is ever stated here: it is the server operator's setting (CLAIM_TOKEN_DAYS), and the date comes with each token.
  is AppError.Gone -> strings.get(when (condition) { "expired" -> R.string.error_token_expired; "used" -> R.string.error_token_used; else -> R.string.error_token_gone })
  is AppError.Network -> strings.get(R.string.error_network)
  else -> userMessage ?: strings.get(R.string.error_generic)
}

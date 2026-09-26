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
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.InvitationToken
import me.paxana.abcmailbox.domain.InviteCode
import me.paxana.abcmailbox.domain.Invitation
import me.paxana.abcmailbox.domain.PasswordRules
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.nav.JoinRoute
import javax.inject.Inject

data class JoinUiState(
  val code: String = "",
  val invitation: Invitation? = null,
  val username: String = "",
  val password: String = "",
  val confirm: String = "",
  val email: String = "",
  val name: String = "",
  val showPassword: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  /** The code was refused for good (used, cancelled, expired, never issued): show the "ask for another" state. */
  val codeDead: Boolean = false,
  /** The account was made and signed in from this screen: the session that follows is the new one, and the screen may leave. */
  val joined: Boolean = false,
  /** A 24-character token was typed here: an invitation (or a claim) token, which the invitation screen checks. */
  val invitationToken: String? = null,
  /** The pen name as typed and checked (API PR #120); optional, so an empty one never blocks. */
  val penName: PenNameState = PenNameState(),
) {
  val passwordsMatch: Boolean get() = password == confirm
  val canCheck: Boolean get() = !busy && code.isNotBlank()
  val canJoin: Boolean get() = !busy && invitation != null && username.trim().length in 3..16 && password.length >= PasswordRules.MIN_LENGTH && passwordsMatch && !penName.blocks
}

/**
 * Joining with an invite code (API PR #116), in two steps like a claim: check the code, so the screen can say
 * which group is vouching and until when, then choose a username and password. The code's format is checked
 * locally first, because the public check is rate limited. Unlike a claim there is nothing to take over: the
 * account is new, and the person's from the first request.
 */
@HiltViewModel
class JoinViewModel(
  private val sessions: SessionRepository,
  route: JoinRoute,
  private val strings: Strings,
  penNames: PenNameRepository,
) : ViewModel() {

  @Inject
  constructor(sessions: SessionRepository, strings: Strings, penNames: PenNameRepository, savedStateHandle: SavedStateHandle) : this(sessions, savedStateHandle.toRoute<JoinRoute>(), strings, penNames)

  private val _ui = MutableStateFlow(JoinUiState(code = route.code?.let { InviteCode.pretty(it) }.orEmpty()))
  val ui: StateFlow<JoinUiState> = _ui.asStateFlow()
  private val penName = PenNameChecker(viewModelScope, penNames, strings)

  /** Signed in already: an invite code makes a new account, which must not replace the session unasked. */
  private val signedInAs: String? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.username

  init {
    viewModelScope.launch { penName.state.collect { st -> _ui.update { it.copy(penName = st) } } }
    // Arrived by the slip's link: check the code straight away, unless somebody is signed in (the screen says so instead).
    if (route.code != null && InviteCode.isWellFormed(route.code) && signedInAs == null) check()
  }

  fun onPenNameChange(v: String) { penName.onChange(v); _ui.update { it.copy(error = null) } }

  fun signOut() { viewModelScope.launch { sessions.logout() } }

  fun onCodeChange(v: String) = _ui.update { it.copy(code = v, error = null, codeDead = false, invitation = null) }
  fun onUsernameChange(v: String) = _ui.update { it.copy(username = v, error = null) }
  fun onPasswordChange(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onConfirmChange(v: String) = _ui.update { it.copy(confirm = v, error = null) }
  fun onEmailChange(v: String) = _ui.update { it.copy(email = v, error = null) }
  fun onNameChange(v: String) = _ui.update { it.copy(name = v, error = null) }
  fun onToggleShowPassword() = _ui.update { it.copy(showPassword = !it.showPassword) }
  fun startOver() = _ui.update { JoinUiState() }
  fun invitationTokenHandedOn() = _ui.update { it.copy(invitationToken = null) }

  fun check() {
    val typed = _ui.value.code
    signedInAs?.let { name -> _ui.update { it.copy(error = strings.get(R.string.join_signed_in, name)) }; return }
    // Twice an invite code's length is an invitation token (README, "Invitations"): the one box sends each to its own screen.
    if (InvitationToken.isWellFormed(typed)) { _ui.update { it.copy(invitationToken = InvitationToken.normalise(typed)) }; return }
    InviteCode.problem(typed, strings)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
    _ui.update { it.copy(busy = true, error = null, codeDead = false) }
    viewModelScope.launch {
      when (val r = sessions.joinInfo(InviteCode.normalise(typed))) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, invitation = r.value, code = InviteCode.pretty(typed)) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, codeDead = r.error.isDeadCode, error = r.error.toJoinMessage(strings)) }
      }
    }
  }

  fun join() {
    val s = _ui.value
    if (!s.canJoin) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = sessions.join(InviteCode.normalise(s.code), s.username, s.password, s.email, s.name, s.penName.value.ifBlank { null })) {
        // Success flips the session to signed-in; the screen leaves on its own.
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "", joined = true) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, codeDead = r.error.isDeadCode, error = r.error.toJoinMessage(strings)) }
      }
    }
  }
}

private val AppError.isDeadCode: Boolean get() = this is AppError.Gone || this is AppError.NotFound

internal fun AppError.toJoinMessage(strings: Strings): String = when (this) {
  is AppError.NotFound -> strings.get(R.string.error_invite_unknown)
  // Worded on `condition` (API PR #117), never on the sentence. No lifetime is stated: the date came with the check.
  is AppError.Gone -> strings.get(
    when (condition) {
      "used" -> R.string.error_invite_used
      "cancelled" -> R.string.error_invite_cancelled
      "expired" -> R.string.error_invite_expired
      "inactive" -> R.string.error_invite_inactive
      else -> R.string.error_invite_gone
    },
  )
  // A 400 (a taken username, say) leaves the code unspent, and the screen should say so: a slip is not burnt by a typo.
  is AppError.Validation -> listOfNotNull(userMessage, strings.get(R.string.invite_code_kept)).joinToString(" ")
  is AppError.Network -> strings.get(R.string.error_network)
  else -> userMessage ?: strings.get(R.string.error_generic)
}

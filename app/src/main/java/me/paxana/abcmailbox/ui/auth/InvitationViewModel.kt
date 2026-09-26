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
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.GroupInvitation
import me.paxana.abcmailbox.domain.InvitationAccepted
import me.paxana.abcmailbox.domain.InvitationToken
import me.paxana.abcmailbox.domain.InviteCode
import me.paxana.abcmailbox.domain.NewGroupProfile
import me.paxana.abcmailbox.domain.PasswordRules
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.nav.InvitationRoute
import javax.inject.Inject

data class InvitationUiState(
  val token: String = "",
  val invitation: GroupInvitation? = null,
  val username: String = "",
  val password: String = "",
  val confirm: String = "",
  val email: String = "",
  val name: String = "",
  val group: NewGroupProfile = NewGroupProfile(name = "", city = ""),
  val showPassword: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  /** The token was refused for good (expired, used, withdrawn, its group inactive): show the "ask for another" state. */
  val tokenDead: Boolean = false,
  /** No invitation has this token. Claim tokens are 24 characters too, so the screen offers to try it as one. */
  val maybeClaimToken: Boolean = false,
  /** A twelve-character invite code was typed here: the screen hands it to the join screen. */
  val inviteCode: String? = null,
  /** Accepted and signed in from this screen: the session that follows is the new one, and the screen may leave. */
  val accepted: InvitationAccepted? = null,
) {
  val passwordsMatch: Boolean get() = password == confirm
  val canCheck: Boolean get() = !busy && token.isNotBlank()
  /** The API requires an email for a group admin, and a name and a location for a new group. */
  val canAccept: Boolean get() = !busy && invitation != null && username.trim().length in 3..16 && password.length >= PasswordRules.MIN_LENGTH &&
    passwordsMatch && EMAIL.matches(email.trim()) &&
    (invitation.kind != GroupInvitation.Kind.GROUP || (group.name.isNotBlank() && group.city.isNotBlank()))

  companion object {
    /** Enough to catch a missing @ before the server's own check; the server's word is final. */
    private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
  }
}

/**
 * Accepting an invitation (README, "Invitations"), the only way a group admin comes to be in end-to-end mode: in two
 * steps like a join, the token checked first so the screen can say who is inviting, then the account, and for a new
 * group its profile. The token's format is checked here first, because the public check is rate limited; an invite
 * code typed here goes to the join screen instead, and a token no invitation has may be a claim token.
 */
@HiltViewModel
class InvitationViewModel(
  private val sessions: SessionRepository,
  route: InvitationRoute,
  private val strings: Strings,
) : ViewModel() {

  @Inject
  constructor(sessions: SessionRepository, strings: Strings, savedStateHandle: SavedStateHandle) : this(sessions, savedStateHandle.toRoute<InvitationRoute>(), strings)

  private val _ui = MutableStateFlow(InvitationUiState(token = route.token?.let { InvitationToken.pretty(it) }.orEmpty()))
  val ui: StateFlow<InvitationUiState> = _ui.asStateFlow()

  /** Signed in already: an invitation makes a new account, which must not replace the session unasked. */
  private val signedInAs: String? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.username

  init {
    // Handed on from the join screen, or opened with a token: check it straight away, unless somebody is signed in.
    if (route.token != null && InvitationToken.isWellFormed(route.token) && signedInAs == null) check()
  }

  fun signOut() { viewModelScope.launch { sessions.logout() } }

  fun onTokenChange(v: String) = _ui.update { it.copy(token = v, error = null, tokenDead = false, maybeClaimToken = false, invitation = null) }
  fun onUsernameChange(v: String) = _ui.update { it.copy(username = v, error = null) }
  fun onPasswordChange(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onConfirmChange(v: String) = _ui.update { it.copy(confirm = v, error = null) }
  fun onEmailChange(v: String) = _ui.update { it.copy(email = v, error = null) }
  fun onNameChange(v: String) = _ui.update { it.copy(name = v, error = null) }
  fun onGroupChange(change: (NewGroupProfile) -> NewGroupProfile) = _ui.update { it.copy(group = change(it.group), error = null) }
  fun onToggleShowPassword() = _ui.update { it.copy(showPassword = !it.showPassword) }
  fun inviteCodeHandedOn() = _ui.update { it.copy(inviteCode = null) }
  fun startOver() = _ui.update { InvitationUiState() }

  fun check() {
    val typed = _ui.value.token
    signedInAs?.let { name -> _ui.update { it.copy(error = strings.get(R.string.invitation_signed_in, name)) }; return }
    // Twelve characters is an invite code, not a mistyped token: it belongs on the join screen.
    if (InviteCode.isWellFormed(typed)) { _ui.update { it.copy(inviteCode = InviteCode.normalise(typed)) }; return }
    InvitationToken.problem(typed, strings)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
    _ui.update { it.copy(busy = true, error = null, tokenDead = false, maybeClaimToken = false) }
    viewModelScope.launch {
      when (val r = sessions.invitationInfo(InvitationToken.normalise(typed))) {
        is ApiResult.Success -> _ui.update { st ->
          val inv = r.value
          // A new group's name starts as the inviter wrote it; the invitee may correct it.
          val group = if (inv.kind == GroupInvitation.Kind.GROUP && st.group.name.isBlank()) st.group.copy(name = inv.inviteeName) else st.group
          st.copy(busy = false, invitation = inv, token = InvitationToken.pretty(typed), group = group)
        }
        is ApiResult.Failure -> _ui.update {
          // A 400 here is a kind of invitation this release does not know: said as it is, with no form to correct.
          val message = (r.error as? AppError.Validation)?.userMessage ?: r.error.toInvitationMessage(strings)
          it.copy(busy = false, tokenDead = r.error is AppError.Gone, maybeClaimToken = r.error is AppError.NotFound, error = message)
        }
      }
    }
  }

  fun accept() {
    val s = _ui.value
    val invitation = s.invitation ?: return
    if (!s.canAccept) return
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      val group = s.group.takeIf { invitation.kind == GroupInvitation.Kind.GROUP }
      when (val r = sessions.acceptInvitation(InvitationToken.normalise(s.token), s.username, s.password, s.email, s.name, group, invitation.groupFields)) {
        // Success flips the session to signed-in; the screen leaves on its own.
        is ApiResult.Success -> _ui.update { it.copy(busy = false, password = "", confirm = "", accepted = r.value) }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, tokenDead = r.error is AppError.Gone, error = r.error.toInvitationMessage(strings)) }
      }
    }
  }
}

internal fun AppError.toInvitationMessage(strings: Strings): String = when (this) {
  is AppError.NotFound -> strings.get(R.string.error_invitation_unknown)
  // Worded on `condition`, never on the sentence.
  is AppError.Gone -> strings.get(
    when (condition) {
      "expired" -> R.string.error_invitation_expired
      "accepted" -> R.string.error_invitation_accepted
      "revoked" -> R.string.error_invitation_revoked
      "inactive" -> R.string.error_invitation_inactive
      else -> R.string.error_invitation_gone
    },
  )
  // A refused acceptance (a taken username, say) leaves nothing behind and the invitation usable, and should say so.
  is AppError.Validation -> listOfNotNull(userMessage, strings.get(R.string.invitation_kept)).joinToString(" ")
  is AppError.Network -> strings.get(R.string.error_network)
  else -> userMessage ?: strings.get(R.string.error_generic)
}

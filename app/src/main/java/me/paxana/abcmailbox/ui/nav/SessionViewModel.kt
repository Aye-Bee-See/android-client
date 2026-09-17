package me.paxana.abcmailbox.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import javax.inject.Inject

/** Exposes sign-in state to the shell. The repository owns it; this just hands it to Compose. */
@HiltViewModel
class SessionViewModel @Inject constructor(
  private val repository: SessionRepository,
  modes: EncryptionModeRepository,
) : ViewModel() {
  val state: StateFlow<SessionState> = repository.state
  val expired: SharedFlow<Unit> = repository.expired
  val pendingRecoveryCode: StateFlow<String?> = repository.pendingRecoveryCode
  val keysLocked: StateFlow<Boolean> = repository.keysLocked
  val mode: StateFlow<EncryptionMode> = modes.mode

  init {
    // Ask the server which letter contract it speaks, once per launch.
    viewModelScope.launch { modes.refresh() }
  }

  fun recoveryCodeSaved() = repository.recoveryCodeSaved()
}

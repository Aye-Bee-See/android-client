package me.paxana.abcmailbox.ui.nav

import me.paxana.abcmailbox.data.offline.OfflineDirectory
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.repo.DirectorySource
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
  directory: DirectoryRepository,
  offline: OfflineDirectory,
) : ViewModel() {
  /** Live, or the copy saved on the phone: the shell says so above the directory when it is the copy. */
  val directorySource: StateFlow<DirectorySource> = directory.source
  val state: StateFlow<SessionState> = repository.state
  val expired: SharedFlow<Unit> = repository.expired
  val pendingRecoveryCode: StateFlow<String?> = repository.pendingRecoveryCode
  val keysLocked: StateFlow<Boolean> = repository.keysLocked
  val mode: StateFlow<EncryptionMode> = modes.mode

  init {
    // Ask the server which letter contract it speaks, once per launch.
    viewModelScope.launch { modes.refresh() }
    // Keep the offline copy of the directory fresh: at most one quiet download a day, and only if the server answers.
    viewModelScope.launch { offline.downloadIfOlderThan(maxAgeHours = 24) }
  }

  fun recoveryCodeSaved() = repository.recoveryCodeSaved()
}

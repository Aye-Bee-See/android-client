package me.paxana.abcmailbox.ui.nav

import me.paxana.abcmailbox.domain.Activity
import me.paxana.abcmailbox.data.activity.ActivityRepository
import me.paxana.abcmailbox.data.activity.ActivityScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import me.paxana.abcmailbox.data.repo.OutboxScheduler
import me.paxana.abcmailbox.data.repo.OutboxRepository
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
  outbox: OutboxRepository,
  outboxScheduler: OutboxScheduler,
  private val activity: ActivityRepository,
  activityScheduler: ActivityScheduler,
) : ViewModel() {
  /** Feed entries not yet seen (a reply arrived, a letter was mailed…): added to the Inbox tab's badge. */
  val unreadActivity: StateFlow<Int> = activity.unread
  /** News arriving while the app is on screen; the shell says it in a line at the bottom. */
  val arrivals: SharedFlow<List<Activity>> = activity.arrivals

  /** The Inbox is on screen: what the feed had to say has been seen. Also clears the system notification. */
  fun inboxSeen() { viewModelScope.launch { activity.markAllRead() } }
  /** Letters waiting in the outbox, for the badge on the Inbox tab. */
  val unsentCount: StateFlow<Int> = outbox.items().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
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
    // Whoever is signed in gets their feed looked at now (quietly: they are in the app, the badge is enough) and a few times a day from here on.
    viewModelScope.launch { repository.state.collect { if (it is SessionState.SignedIn) { activityScheduler.keepChecking(); activity.sync(announce = false) } } }
    // Letters queued under this account wait through sign-outs and restarts; whenever someone is signed in, make sure a send is scheduled.
    viewModelScope.launch { repository.state.collect { if (it is SessionState.SignedIn && outbox.hasWaiting()) outboxScheduler.schedule() } }
  }

  fun recoveryCodeSaved() = repository.recoveryCodeSaved()
}

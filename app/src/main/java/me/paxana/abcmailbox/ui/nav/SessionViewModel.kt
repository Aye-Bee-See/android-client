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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
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
  private val eraser: me.paxana.abcmailbox.data.account.AccountEraser,
) : ViewModel() {
  /** The receipt for an account just deleted. Lives above every screen, because every screen is rebuilt when the session goes. */
  val farewell: StateFlow<me.paxana.abcmailbox.data.account.DeletionReport?> = eraser.farewell
  fun farewellSeen() = eraser.farewellSeen()
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
  /** The recovery code screen's upload: under way, or what went wrong (the code stays on screen for another try). */
  data class RecoveryUpload(val busy: Boolean = false, val error: AppError? = null)
  private val _recoveryUpload = MutableStateFlow(RecoveryUpload())
  val recoveryUpload: StateFlow<RecoveryUpload> = _recoveryUpload.asStateFlow()

  /** Told in a line at the bottom once the code screen has gone. */
  sealed interface RecoveryOutcome {
    /** The keys are up, and the server sealed this many earlier letters to them. */
    data class Uploaded(val lettersCaughtUp: Int) : RecoveryOutcome
    /** Another device set this account's key first: the code just shown opens nothing. */
    data object KeysMadeElsewhere : RecoveryOutcome
  }
  private val _recoveryOutcome = MutableSharedFlow<RecoveryOutcome>(extraBufferCapacity = 1)
  val recoveryOutcome: SharedFlow<RecoveryOutcome> = _recoveryOutcome.asSharedFlow()
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

  fun recoveryCodeSaved() {
    if (_recoveryUpload.value.busy) return
    _recoveryUpload.value = RecoveryUpload(busy = true)
    viewModelScope.launch {
      val r = repository.recoveryCodeSaved()
      // Whatever happened, a code that is gone (uploaded, made elsewhere, or the session ended) leaves nothing to show.
      _recoveryUpload.value = if (r is ApiResult.Failure && repository.pendingRecoveryCode.value != null) RecoveryUpload(error = r.error) else RecoveryUpload()
      when {
        r is ApiResult.Success && r.value > 0 -> _recoveryOutcome.emit(RecoveryOutcome.Uploaded(r.value))
        r is ApiResult.Failure && r.error is AppError.Conflict -> _recoveryOutcome.emit(RecoveryOutcome.KeysMadeElsewhere)
      }
    }
  }
}

package me.paxana.abcmailbox.ui.nav

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import javax.inject.Inject

/** Exposes sign-in state to the shell. The repository owns it; this just hands it to Compose. */
@HiltViewModel
class SessionViewModel @Inject constructor(repository: SessionRepository) : ViewModel() {
  val state: StateFlow<SessionState> = repository.state
  val expired: SharedFlow<Unit> = repository.expired
}

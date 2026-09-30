package me.paxana.abcmailbox.ui.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.session.Role
import me.paxana.abcmailbox.data.session.SessionState

/**
 * A pen name is required when an account is made (API #168, 30 September 2026), and a writer's account made before
 * then may have none: its letters are signed with the display name, which may be the person's real one. Such an
 * account is asked for one once each time the app finds it signed in, on the pen name screen, which it may leave.
 *
 * Only a writer's account is asked: staff sign no letters, and the API asks none of them. The session's copy of the
 * user says whether to look; the server's list of names says whether to ask, because that copy may be older.
 */
class PenNamePrompt(scope: CoroutineScope, sessions: Flow<SessionState>, private val penNames: PenNameRepository) {
  private val _ask = MutableStateFlow(false)
  /** The shell should open the pen name screen now. */
  val ask: StateFlow<Boolean> = _ask.asStateFlow()
  private var lookedFor: Int? = null

  init {
    scope.launch {
      sessions.collect { st ->
        val user = (st as? SessionState.SignedIn)?.session?.user
        if (user == null) { lookedFor = null; _ask.value = false; return@collect }
        if (user.role != Role.USER || user.penName != null || lookedFor == user.id) return@collect
        lookedFor = user.id
        // No answer (offline): not asked this time, rather than asked on a guess.
        val r = penNames.names()
        if (r is ApiResult.Success && r.value.current == null) _ask.value = true
      }
    }
  }

  /** The screen was opened: not again until the next sign-in or launch. */
  fun asked() { _ask.value = false }
}

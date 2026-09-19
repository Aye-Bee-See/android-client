package me.paxana.abcmailbox.ui.account

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.offline.OfflineDirectory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.dev.DevServerRepository
import me.paxana.abcmailbox.data.session.SessionRepository
import javax.inject.Inject

data class AccountUiState(
  val signingOut: Boolean = false,
  val notice: String? = null,
  val serverUrl: String = "",
  val serverDefault: String = "",
  val serverOverridden: Boolean = false,
  val serverDialog: Boolean = false,
  val serverChecking: Boolean = false,
  val serverResult: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
  private val sessions: SessionRepository,
  private val devServer: DevServerRepository,
  private val offline: OfflineDirectory,
  private val strings: Strings,
  private val activityScheduler: me.paxana.abcmailbox.data.activity.ActivityScheduler,
) : ViewModel() {

  /**
   * Developer tool: does what the push doorbell does, after a pause long enough to leave the app, so the
   * whole path (wake, fetch the feed, word it, notify) can be tried without Firebase.
   */
  fun simulatePush() { viewModelScope.launch { kotlinx.coroutines.delay(8_000); activityScheduler.checkNow() } }

  private val _uiState = MutableStateFlow(AccountUiState(serverUrl = devServer.baseUrl.value, serverDefault = devServer.default, serverOverridden = devServer.isOverridden))
  val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

  private var buildTaps = 0

  /** Five taps on the build line open the server dialog (debug builds only; the screen gates it). */
  fun onBuildLineTap() {
    buildTaps++
    if (buildTaps >= 5) { buildTaps = 0; _uiState.update { it.copy(serverDialog = true, serverResult = null) } }
  }

  fun closeServerDialog() = _uiState.update { it.copy(serverDialog = false) }

  fun saveServer(input: String) {
    _uiState.update { it.copy(serverChecking = true, serverResult = null) }
    viewModelScope.launch {
      val saved = devServer.set(input)
      val result = saved.fold(
        onSuccess = { url ->
          when (val r = devServer.check()) {
            is ApiResult.Success -> "Reachable: ${r.value}"
            is ApiResult.Failure -> "Saved $url, but /health failed: ${r.error.userMessage ?: "no connection"}. Is the API running and on the same Wi-Fi?"
          }
        },
        onFailure = { it.message ?: "Invalid URL" },
      )
      _uiState.update { it.copy(serverChecking = false, serverResult = result, serverUrl = devServer.baseUrl.value, serverOverridden = devServer.isOverridden) }
      // The saved directory belongs to the server it came from; a different server needs its own copy.
      offline.download()
    }
  }

  fun resetServer() {
    viewModelScope.launch {
      devServer.reset()
      _uiState.update { it.copy(serverResult = "Back to the default.", serverUrl = devServer.baseUrl.value, serverOverridden = devServer.isOverridden) }
      offline.download()
    }
  }

  fun signOut(everywhere: Boolean) {
    _uiState.update { it.copy(signingOut = true, notice = null) }
    viewModelScope.launch {
      val result = sessions.logout(everywhere)
      val notice = when (result) {
        is ApiResult.Success -> strings.get(if (everywhere) R.string.notice_signed_out_everywhere else R.string.notice_signed_out)
        is ApiResult.Failure -> strings.get(R.string.notice_signed_out_server_untold, result.error.userMessage ?: strings.get(R.string.no_connection_short))
      }
      _uiState.update { it.copy(signingOut = false, notice = notice) }
    }
  }

  fun noticeShown() = _uiState.update { it.copy(notice = null) }
}

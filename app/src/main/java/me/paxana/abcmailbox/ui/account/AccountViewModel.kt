package me.paxana.abcmailbox.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.session.SessionRepository
import javax.inject.Inject

data class AccountUiState(val signingOut: Boolean = false, val notice: String? = null)

@HiltViewModel
class AccountViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {

  private val _uiState = MutableStateFlow(AccountUiState())
  val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

  fun signOut(everywhere: Boolean) {
    _uiState.update { it.copy(signingOut = true, notice = null) }
    viewModelScope.launch {
      val result = sessions.logout(everywhere)
      val notice = when (result) {
        is ApiResult.Success -> if (everywhere) "Signed out on every device." else "Signed out."
        is ApiResult.Failure -> "Signed out on this device. The server could not be told: ${result.error.userMessage ?: "no connection"}."
      }
      _uiState.update { it.copy(signingOut = false, notice = notice) }
    }
  }

  fun noticeShown() = _uiState.update { it.copy(notice = null) }
}

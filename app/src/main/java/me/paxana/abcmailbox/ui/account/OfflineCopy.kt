package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.db.DirectoryCounts
import me.paxana.abcmailbox.data.offline.OfflineDirectory
import me.paxana.abcmailbox.data.offline.OfflineStatus
import me.paxana.abcmailbox.ui.common.longDate
import javax.inject.Inject

data class OfflineCopyUiState(val updating: Boolean = false, val message: String? = null)

@HiltViewModel
class OfflineCopyViewModel @Inject constructor(private val offline: OfflineDirectory) : ViewModel() {
  val status: StateFlow<OfflineStatus> = offline.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineStatus(null, DirectoryCounts(0, 0, 0)))
  private val _ui = MutableStateFlow(OfflineCopyUiState())
  val ui: StateFlow<OfflineCopyUiState> = _ui.asStateFlow()

  fun update() {
    if (_ui.value.updating) return
    _ui.update { OfflineCopyUiState(updating = true) }
    viewModelScope.launch {
      val r = offline.download()
      _ui.update { OfflineCopyUiState(message = when (r) {
        is ApiResult.Success -> "Updated just now."
        is ApiResult.Failure -> "Could not update: ${r.error.userMessage ?: "no connection"}. The earlier copy is untouched."
      }) }
    }
  }
}

/**
 * On the Account tab for everyone, signed in or not: what the phone can show without a
 * connection, and a way to refresh it before going somewhere with no signal.
 */
@Composable
fun OfflineCopySection(modifier: Modifier = Modifier, viewModel: OfflineCopyViewModel = hiltViewModel()) {
  val status by viewModel.status.collectAsStateWithLifecycle()
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Offline directory", style = MaterialTheme.typography.titleSmall)
    val saved = status.savedAt
    Text(
      if (saved == null) "Nothing is saved on this phone yet. The directory is downloaded by itself when there is a connection."
      else "Saved ${saved.longDate()}: ${status.counts.prisoners} prisoners, ${status.counts.facilities} facilities, ${status.counts.groups} groups. " +
        "Addresses and mail rules can be looked up without a connection; it refreshes by itself about once a day.",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = viewModel::update, enabled = !ui.updating) { Text(if (ui.updating) "Updating…" else "Update now") }
    ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}

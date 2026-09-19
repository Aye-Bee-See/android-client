package me.paxana.abcmailbox.ui.account

import me.paxana.abcmailbox.text.Strings
import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
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
class OfflineCopyViewModel @Inject constructor(private val offline: OfflineDirectory, private val strings: Strings) : ViewModel() {
  val status: StateFlow<OfflineStatus> = offline.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineStatus(null, DirectoryCounts(0, 0, 0)))
  private val _ui = MutableStateFlow(OfflineCopyUiState())
  val ui: StateFlow<OfflineCopyUiState> = _ui.asStateFlow()

  fun update() {
    if (_ui.value.updating) return
    _ui.update { OfflineCopyUiState(updating = true) }
    viewModelScope.launch {
      val r = offline.download()
      _ui.update { OfflineCopyUiState(message = when (r) {
        is ApiResult.Success -> strings.get(R.string.offline_updated)
        is ApiResult.Failure -> strings.get(R.string.offline_update_failed, r.error.userMessage ?: strings.get(R.string.no_connection_short))
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
    Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleSmall)
    val saved = status.savedAt
    Text(
      if (saved == null) stringResource(R.string.offline_nothing_saved)
      else stringResource(
        R.string.offline_saved, saved.longDate(),
        pluralStringResource(R.plurals.count_prisoners, status.counts.prisoners, status.counts.prisoners),
        pluralStringResource(R.plurals.count_facilities, status.counts.facilities, status.counts.facilities),
        pluralStringResource(R.plurals.count_groups, status.counts.groups, status.counts.groups),
      ),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = viewModel::update, enabled = !ui.updating) { Text(stringResource(if (ui.updating) R.string.action_updating else R.string.action_update_now)) }
    ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}

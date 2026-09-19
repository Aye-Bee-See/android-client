package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.push.PushProvider
import me.paxana.abcmailbox.data.push.PushRegistrar
import me.paxana.abcmailbox.text.Strings
import javax.inject.Inject

data class PushSettingUiState(val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class PushSettingViewModel @Inject constructor(private val push: PushRegistrar, private val strings: Strings) : ViewModel() {
  val availability: PushProvider.Availability get() = push.availability
  val enabled: StateFlow<Boolean> = push.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
  private val _ui = MutableStateFlow(PushSettingUiState())
  val ui: StateFlow<PushSettingUiState> = _ui.asStateFlow()

  fun set(on: Boolean) {
    if (_ui.value.busy) return
    _ui.update { PushSettingUiState(busy = true) }
    viewModelScope.launch {
      val message = if (on) when (val r = push.turnOn()) {
        // Turned on either way. If the server cannot ring phones yet, say so, or the switch would seem to do nothing.
        is ApiResult.Success -> strings.get(if (r.value) R.string.push_on else R.string.push_on_not_deliverable)
        is ApiResult.Failure -> strings.get(R.string.push_failed, r.error.userMessage ?: strings.get(R.string.no_connection_short))
      } else when (val r = push.turnOff()) {
        is ApiResult.Success -> strings.get(R.string.push_off)
        // It is off on this phone regardless; the server will forget the device at sign-out if it could not be told now.
        is ApiResult.Failure -> strings.get(R.string.push_off)
      }
      _ui.update { PushSettingUiState(message = message) }
    }
  }
}

/**
 * Off until the person turns it on, with the trade stated where the switch is: prompt news, against
 * Google learning that this phone has this app and when it is rung. Hidden in a build made without a
 * Firebase project, except in developer builds, where seeing why it is unavailable is the point.
 */
@Composable
fun PushSetting(modifier: Modifier = Modifier, viewModel: PushSettingViewModel = hiltViewModel()) {
  val availability = viewModel.availability
  if (availability == PushProvider.Availability.NOT_CONFIGURED && !BuildConfig.DEV_TOOLS) return
  val enabled by viewModel.enabled.collectAsStateWithLifecycle()
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val ready = availability == PushProvider.Availability.READY

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      Modifier.fillMaxWidth().toggleable(value = enabled, enabled = ready && !ui.busy, role = Role.Switch, onValueChange = viewModel::set).padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(R.string.push_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Switch(checked = enabled, onCheckedChange = null, enabled = ready && !ui.busy)
    }
    Text(stringResource(R.string.push_explained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val status = ui.message ?: when (availability) {
      PushProvider.Availability.NOT_CONFIGURED -> stringResource(R.string.push_unavailable_build)
      PushProvider.Availability.NO_SERVICE -> stringResource(R.string.push_unavailable_device)
      PushProvider.Availability.READY -> null
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
  }
}

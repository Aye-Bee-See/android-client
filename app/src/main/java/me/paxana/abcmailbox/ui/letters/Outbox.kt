package me.paxana.abcmailbox.ui.letters

import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.repo.OutboxItem
import me.paxana.abcmailbox.data.repo.OutboxRepository
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.shortDateTime
import javax.inject.Inject

@HiltViewModel
class OutboxViewModel @Inject constructor(private val outbox: OutboxRepository) : ViewModel() {
  val items: StateFlow<List<OutboxItem>> = outbox.items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  private val _trying = MutableStateFlow(false)
  val trying: StateFlow<Boolean> = _trying.asStateFlow()

  /** For someone watching the screen: try now rather than wait for the system to notice the network. */
  fun tryNow() {
    if (_trying.value) return
    _trying.value = true
    viewModelScope.launch { outbox.flush(); _trying.value = false }
  }
  fun retry(item: OutboxItem) { viewModelScope.launch { outbox.retry(item.id); outbox.flush() } }
  fun delete(item: OutboxItem) { viewModelScope.launch { outbox.delete(item.id) } }
}

/**
 * Letters written without a connection, above the inbox until they are gone. A waiting letter
 * needs nothing from the writer; a refused one does, and says what, in the server's words.
 */
@Composable
fun OutboxSection(onEdit: (OutboxItem) -> Unit, modifier: Modifier = Modifier, viewModel: OutboxViewModel = hiltViewModel()) {
  val items by viewModel.items.collectAsStateWithLifecycle()
  val trying by viewModel.trying.collectAsStateWithLifecycle()
  var confirmDelete by remember { mutableStateOf<OutboxItem?>(null) }
  if (items.isEmpty()) return

  Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionTitle(stringResource(R.string.outbox_title), Modifier.padding(top = 0.dp))
      Text(stringResource(R.string.outbox_explained), style = MaterialTheme.typography.bodySmall)
      items.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          val to = stringResource(if (item.payload.fromPrisoner) R.string.outbox_reply_from else R.string.outbox_to, item.payload.prisonerName) + (item.payload.writingAs?.let { stringResource(R.string.outbox_as, it) } ?: "")
          Text(to, style = MaterialTheme.typography.titleSmall)
          Text(
            stringResource(R.string.outbox_written, item.queuedAt.shortDateTime()).let { written -> item.payload.attachments.size.takeIf { it > 0 }?.let { n -> stringResource(R.string.outbox_written_with_files, written, pluralStringResource(R.plurals.outbox_files, n, n)) } ?: written },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          item.problem?.let { Text(if (item.letterWasSent) it else stringResource(R.string.outbox_not_sent, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
              // The server has this letter; opening it again would post a second copy. All that is left is to take note.
              item.letterWasSent -> TextButton(onClick = { viewModel.delete(item) }) { Text(stringResource(R.string.action_dismiss)) }
              else -> {
                TextButton(onClick = { onEdit(item) }) { Text(stringResource(R.string.action_edit)) }
                if (item.problem != null) TextButton(onClick = { viewModel.retry(item) }) { Text(stringResource(R.string.action_try_as_is)) }
                TextButton(onClick = { confirmDelete = item }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
              }
            }
          }
        }
      }
      if (items.any { it.problem == null }) TextButton(onClick = viewModel::tryNow, enabled = !trying) { Text(stringResource(if (trying) R.string.action_trying else R.string.action_try_send_now)) }
    }
  }

  confirmDelete?.let { item ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text(stringResource(R.string.outbox_delete_title)) },
      text = { Text(stringResource(R.string.outbox_delete_text)) },
      confirmButton = { TextButton(onClick = { viewModel.delete(item); confirmDelete = null }) { Text(stringResource(R.string.action_delete)) } },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_keep_it)) } },
    )
  }
}

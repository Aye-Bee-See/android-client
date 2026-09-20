package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.ui.common.ReloadOnNews
import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.QueueItem
import me.paxana.abcmailbox.ui.common.ChipRow
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.PagedList
import me.paxana.abcmailbox.ui.common.RecordRow
import me.paxana.abcmailbox.ui.common.shortDate
import me.paxana.abcmailbox.ui.directory.Loadable

/**
 * What a group member sees in place of a writer's inbox, after `inbox-org.html`:
 * the letters waiting to be printed and mailed, the conversations the group can
 * see, and the writers it looks after.
 */
@Composable
fun GroupInbox(
  conversations: @Composable () -> Unit,
  onLetter: (Int) -> Unit,
  onAddWriter: () -> Unit,
  onNewLetter: (writerId: Int?, writerName: String?) -> Unit,
  onHandoff: (ManagedWriter) -> Unit,
  onGroupKey: () -> Unit = {},
) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  Column(Modifier.fillMaxSize()) {
    GroupKeyBanner(onMembers = onGroupKey)
    // Scrollable, so each tab is as wide as its label: with fixed thirds, "Conversations" broke
    // mid-word at large font sizes. One line per label, always.
    PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
      listOf(stringResource(R.string.tab_to_print), stringResource(R.string.tab_conversations), stringResource(R.string.tab_writers)).forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1, softWrap = false) }) }
    }
    // When the group key opens, letters that were locked become readable: rebuilding the tab
    // under a new key re-runs its resume effect, which refreshes the list.
    val keyOpen = hiltViewModel<GroupKeyViewModel>().keyState.collectAsStateWithLifecycle().value is me.paxana.abcmailbox.data.crypto.GroupKeyState.Ready
    androidx.compose.runtime.key(keyOpen) {
      when (tab) {
        0 -> QueueTab(onLetter)
        1 -> conversations()
        else -> WritersTab(onAddWriter, onNewLetter, onHandoff)
      }
    }
  }
}

private val queueStatuses = listOf(LetterStatus.QUEUED, LetterStatus.PRINTED, LetterStatus.MAILED)

@Composable
private fun QueueTab(onLetter: (Int) -> Unit, viewModel: QueueViewModel = hiltViewModel()) {
  val status by viewModel.status.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  LifecycleResumeEffect(Unit) { items.refresh(); onPauseOrDispose { } }
  ReloadOnNews { items.refresh() } // a letter joined the queue
  if (!viewModel.hasGroup) {
    Text(stringResource(R.string.not_in_group), modifier = Modifier.padding(20.dp))
    return
  }
  PagedList(
    items = items,
    emptyText = when (status) { LetterStatus.QUEUED -> stringResource(R.string.queue_empty_queued); LetterStatus.PRINTED -> stringResource(R.string.queue_empty_printed); else -> stringResource(R.string.queue_empty_mailed) },
    header = {
      item("status") {
        ChipRow(queueStatuses.map { it to stringResource(it.labelRes) }, status, { it?.let(viewModel::setStatus) }, allLabel = "", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), showAll = false)
      }
    },
  ) { q -> QueueRow(q, onClick = { onLetter(q.letter.id) }) }
}

@Composable
private fun QueueRow(q: QueueItem, onClick: () -> Unit) {
  val pages = me.paxana.abcmailbox.domain.estimatePages(q.letter.body.length)
  RecordRow(
    title = q.prisoner?.name ?: stringResource(R.string.prisoner_numbered, q.letter.prisonerId ?: 0),
    secondary = q.prisoner?.facility?.let { f -> f.name + (f.country?.let { ", $it" } ?: "") },
    subtitle = listOfNotNull(
      q.letter.createdAt?.shortDate()?.let { stringResource(R.string.written_on, it) },
      pluralStringResource(R.plurals.compose_pages, pages, pages),
      q.letter.attachments.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.outbox_files, it, it) },
    ).joinToString(" · "),
    notice = q.letter.relayNote?.let { stringResource(R.string.note_prefixed, it) },
    onClick = onClick,
  )
}

@Composable
private fun WritersTab(onAddWriter: () -> Unit, onNewLetter: (Int?, String?) -> Unit, onHandoff: (ManagedWriter) -> Unit, viewModel: WritersViewModel = hiltViewModel()) {
  val state by viewModel.writers.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }
  LazyColumn(Modifier.fillMaxSize()) {
    item("anon") {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.writer_anonymous), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.anonymous_explained), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onNewLetter(null, null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.action_new_anonymous)) }
      }
      HorizontalDivider()
    }
    when (val s = state) {
      is Loadable.Loading -> item("loading") { LoadingBox() }
      is Loadable.Failed -> item("error") { ErrorBox(s.error, onRetry = viewModel::load) }
      is Loadable.Loaded -> {
        if (s.value.isEmpty()) item("none") { Text(stringResource(R.string.writers_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        items(s.value, key = { it.id }) { w ->
          Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium)
            w.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
              if (w.hasLiveToken) stringResource(R.string.writer_token_pending, w.tokenExpiresAt?.shortDate().orEmpty()) else stringResource(R.string.writer_unclaimed),
              style = MaterialTheme.typography.labelSmall, color = if (w.hasLiveToken) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = { onNewLetter(w.id, w.name) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.action_new_letter)) }
              TextButton(onClick = { onHandoff(w) }) { Text(stringResource(if (w.hasLiveToken) R.string.action_handoff_token else R.string.action_hand_off_account)) }
            }
          }
          HorizontalDivider()
        }
      }
    }
    item("add") { Button(onClick = onAddWriter, modifier = Modifier.padding(20.dp)) { Text(stringResource(R.string.action_add_a_writer)) } }
  }
}

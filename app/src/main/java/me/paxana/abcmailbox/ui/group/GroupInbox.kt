package me.paxana.abcmailbox.ui.group

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
import androidx.compose.material3.PrimaryTabRow
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
    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
      listOf("To print", "Conversations", "Writers").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
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

private val queueStatuses = listOf(LetterStatus.QUEUED to "Queued", LetterStatus.PRINTED to "Printed", LetterStatus.MAILED to "Mailed")

@Composable
private fun QueueTab(onLetter: (Int) -> Unit, viewModel: QueueViewModel = hiltViewModel()) {
  val status by viewModel.status.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  LifecycleResumeEffect(Unit) { items.refresh(); onPauseOrDispose { } }
  if (!viewModel.hasGroup) {
    Text("This account is not in a group yet. A network admin has to set your group before you can see its letters.", modifier = Modifier.padding(20.dp))
    return
  }
  PagedList(
    items = items,
    emptyText = when (status) { LetterStatus.QUEUED -> "Nothing is waiting to be printed."; LetterStatus.PRINTED -> "Nothing is printed and waiting for the post."; else -> "No mailed letters to show." },
    header = {
      item("status") {
        ChipRow(queueStatuses, status, { it?.let(viewModel::setStatus) }, allLabel = "", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), showAll = false)
      }
    },
  ) { q -> QueueRow(q, onClick = { onLetter(q.letter.id) }) }
}

@Composable
private fun QueueRow(q: QueueItem, onClick: () -> Unit) {
  val pages = me.paxana.abcmailbox.domain.estimatePages(q.letter.body.length)
  RecordRow(
    title = q.prisoner?.name ?: "Prisoner #${q.letter.prisonerId}",
    secondary = q.prisoner?.facility?.let { f -> f.name + (f.country?.let { ", $it" } ?: "") },
    subtitle = listOfNotNull(
      q.letter.createdAt?.shortDate()?.let { "Written $it" },
      "~$pages page${if (pages == 1) "" else "s"}",
      q.letter.attachments.size.takeIf { it > 0 }?.let { "$it file${if (it == 1) "" else "s"}" },
    ).joinToString(" · "),
    notice = q.letter.relayNote?.let { "Note: $it" },
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
        Text("Anonymous writer", style = MaterialTheme.typography.titleMedium)
        Text("Letters with no named writer, for example from a letter writing night. They do not need an account.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onNewLetter(null, null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("New anonymous letter") }
      }
      HorizontalDivider()
    }
    when (val s = state) {
      is Loadable.Loading -> item("loading") { LoadingBox() }
      is Loadable.Failed -> item("error") { ErrorBox(s.error, onRetry = viewModel::load) }
      is Loadable.Loaded -> {
        if (s.value.isEmpty()) item("none") { Text("No managed writers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        items(s.value, key = { it.id }) { w ->
          Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium)
            w.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
              if (w.hasLiveToken) "Claim token pending, expires ${w.tokenExpiresAt?.shortDate()}" else "Unclaimed, no token",
              style = MaterialTheme.typography.labelSmall, color = if (w.hasLiveToken) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = { onNewLetter(w.id, w.name) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("New letter") }
              TextButton(onClick = { onHandoff(w) }) { Text(if (w.hasLiveToken) "Handoff token" else "Hand off account") }
            }
          }
          HorizontalDivider()
        }
      }
    }
    item("add") { Button(onClick = onAddWriter, modifier = Modifier.padding(20.dp)) { Text("Add a writer") } }
  }
}

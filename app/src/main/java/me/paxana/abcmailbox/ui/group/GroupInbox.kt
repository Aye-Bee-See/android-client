package me.paxana.abcmailbox.ui.group

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
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

/** In the order the work happens, with the held letters next to the queued ones they were taken out of. (The same five as the iOS app.) */
private val queueFilters = listOf(QueueFilter.ByStatus(LetterStatus.QUEUED), QueueFilter.Held, QueueFilter.ByStatus(LetterStatus.PRINTED), QueueFilter.ByStatus(LetterStatus.MAILED), QueueFilter.ByStatus(LetterStatus.RETURNED))

@Composable
private fun QueueTab(onLetter: (Int) -> Unit, viewModel: QueueViewModel = hiltViewModel()) {
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  LifecycleResumeEffect(Unit) { items.refresh(); onPauseOrDispose { } }
  ReloadOnNews { items.refresh() } // a letter joined the queue
  if (!viewModel.hasGroup) {
    Text(stringResource(R.string.not_in_group), modifier = Modifier.padding(20.dp))
    return
  }
  val selection by viewModel.selection.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  var confirmMailed by remember { mutableStateOf(false) }
  LaunchedEffect(selection.notice) { selection.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  LaunchedEffect(selection.done) { if (selection.done > 0) items.refresh() }
  val next = viewModel.nextStep

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      PagedList(
        items = items,
        modifier = Modifier.weight(1f),
        emptyText = stringResource(when (val f = filter) {
          QueueFilter.Held -> R.string.queue_empty_held
          is QueueFilter.ByStatus -> when (f.status) { LetterStatus.QUEUED -> R.string.queue_empty_queued; LetterStatus.PRINTED -> R.string.queue_empty_printed; LetterStatus.RETURNED -> R.string.queue_empty_returned; else -> R.string.queue_empty_mailed }
        }),
        header = {
          item("status") {
            ChipRow(queueFilters.map { f -> f to stringResource(when (f) { is QueueFilter.ByStatus -> f.status.labelRes; QueueFilter.Held -> R.string.chip_held }) }, filter, { it?.let(viewModel::setFilter) }, allLabel = "", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), showAll = false)
          }
          // A letter night: thirty letters printed, marked together. Offered where there is a next step to take together;
          // a button, not a long-press, so that it can be found, and found by a screen reader.
          if (next != null && !selection.selecting && items.itemCount > 1) item("select") {
            TextButton(onClick = viewModel::startSelecting, modifier = Modifier.padding(horizontal = 8.dp).testTag("select-several")) { Text(stringResource(R.string.action_select_several)) }
          }
        },
      ) { q ->
        if (!selection.selecting) QueueRow(q, onClick = { onLetter(q.letter.id) })
        else {
          val ticked = q.letter.id in selection.selected.orEmpty()
          val mayTick = !q.letter.isHeld && !selection.busy
          // The whole row is the checkbox. A held letter is shown and cannot be ticked: it is printed on purpose, by itself.
          Row(
            Modifier.fillMaxWidth().toggleable(value = ticked, enabled = mayTick, role = Role.Checkbox, onValueChange = { viewModel.toggle(q) }).padding(start = 8.dp).testTag("pick-${q.letter.id}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = ticked, onCheckedChange = null, enabled = mayTick)
            QueueRow(q, onClick = null, modifier = Modifier.weight(1f))
          }
        }
      }
      if (selection.selecting && next != null) Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = viewModel::stopSelecting, enabled = !selection.busy) { Text(stringResource(R.string.action_cancel)) }
          Text(pluralStringResource(R.plurals.selected_count, selection.count, selection.count), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          Button(
            // "Mailed" cannot be taken back, for one letter or for thirty: the same question first.
            onClick = { if (next == LetterStatus.MAILED) confirmMailed = true else viewModel.markSelected() },
            enabled = selection.count > 0 && !selection.busy, modifier = Modifier.testTag("mark-selected"),
          ) { Text(stringResource(if (next == LetterStatus.MAILED) R.string.action_mark_mailed else R.string.action_mark_printed)) }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selection.selecting) 64.dp else 0.dp)) { Snackbar(it) }
  }

  if (confirmMailed) AlertDialog(
    onDismissRequest = { confirmMailed = false },
    title = { Text(pluralStringResource(R.plurals.mark_many_mailed_title, selection.count, selection.count)) },
    text = { Text(stringResource(R.string.mark_mailed_text)) },
    confirmButton = { TextButton(onClick = { confirmMailed = false; viewModel.markSelected() }) { Text(stringResource(R.string.action_in_the_post)) } },
    dismissButton = { TextButton(onClick = { confirmMailed = false }) { Text(stringResource(R.string.action_not_yet)) } },
  )
}

@Composable
private fun QueueRow(q: QueueItem, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
  val pages = me.paxana.abcmailbox.domain.estimatePages(q.letter.body.length)
  RecordRow(
    title = q.prisoner?.name ?: stringResource(R.string.prisoner_numbered, q.letter.prisonerId ?: 0),
    secondary = q.prisoner?.facility?.let { f -> f.name + (f.country?.let { ", $it" } ?: "") },
    subtitle = listOfNotNull(
      q.letter.createdAt?.shortDate()?.let { stringResource(R.string.written_on, it) },
      pluralStringResource(R.plurals.compose_pages, pages, pages),
      q.letter.attachments.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.outbox_files, it, it) },
    ).joinToString(" · "),
    // A held letter says so before anything else: it is in the list, and it is not to be printed like the others.
    notice = q.letter.heldReason?.takeIf { q.letter.isHeld }?.let { stringResource(it.queueNoticeRes) }
      ?: q.letter.returnReason?.takeIf { q.letter.status == LetterStatus.RETURNED }?.let { stringResource(R.string.queue_came_back, stringResource(it.choiceRes).lowercase()) }
      ?: q.letter.relayNote?.let { stringResource(R.string.note_prefixed, it) },
    onClick = onClick,
    modifier = modifier,
    horizontalPadding = if (onClick == null) 8.dp else 20.dp,
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

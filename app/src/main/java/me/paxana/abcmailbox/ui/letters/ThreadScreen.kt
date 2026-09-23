package me.paxana.abcmailbox.ui.letters

import me.paxana.abcmailbox.ui.common.ReloadOnNews
import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import me.paxana.abcmailbox.ui.common.spokenWithoutArrows
import me.paxana.abcmailbox.ui.common.AttachmentRow
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.OutlinedButton
import me.paxana.abcmailbox.domain.ReturnReason
import me.paxana.abcmailbox.domain.HeldReason
import me.paxana.abcmailbox.domain.Thread
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.Tag
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable

/** After `thread.html`: the conversation as a timeline, newest at the bottom, with a Write button. */
@Composable
fun ThreadScreen(
  onBack: () -> Unit,
  onPrisoner: (Int) -> Unit,
  onWrite: (Int) -> Unit,
  onEdit: (prisonerId: Int, messageId: Int) -> Unit,
  onGroupWrite: (prisonerId: Int, writerId: Int, writerName: String) -> Unit = { _, _, _ -> },
  onRecordReply: (prisonerId: Int, writerUserId: Int) -> Unit = { _, _ -> },
  /** A returned letter, or a held one that must be sealed again: compose opens with its text. `writerId` is set when a group does it for a writer it manages. */
  onSendAgain: (prisonerId: Int, letterId: Int, replacesHeld: Boolean, writerId: Int?, writerName: String?) -> Unit = { _, _, _, _, _ -> },
  viewModel: ThreadViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val strings = rememberStrings() // not context.getString: this follows a language change while the screen is open
  var confirmDelete by remember { mutableStateOf<Int?>(null) }

  LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }
  ReloadOnNews { viewModel.load() } // a reply recorded while this conversation is open appears by itself
  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  LaunchedEffect(ui.openFile) {
    ui.openFile?.let { (file, mime) ->
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
      val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { snackbar.showSnackbar(strings.get(R.string.no_app_opens, mime)) }
      viewModel.fileOpened()
    }
  }

  val thread = (ui.thread as? Loadable.Loaded)?.value
  DetailScaffold(title = thread?.title(rememberStrings()) ?: stringResource(R.string.title_conversation), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val t = ui.thread) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(t.error, onRetry = viewModel::load)
        is Loadable.Loaded -> ThreadBody(
          thread = t.value,
          retentionDays = ui.retentionDays,
          busyMessageId = ui.busyMessageId,
          onPrisoner = onPrisoner,
          onOpen = viewModel::open,
          showWriter = ui.isStaff,
          mayChange = me.paxana.abcmailbox.domain.mayChangeLetters(ui.isStaff, ui.staffGroupId, t.value.writer),
          onEdit = { onEdit(t.value.prisonerId, it) },
          onDelete = { confirmDelete = it },
          onChooseRelay = { viewModel.askWhoMails(it, t.value.prisonerId) },
          onSendAgain = { letterId, replacesHeld ->
            // A group sends again as the writer whose letter it was, unless that is its own anonymous writer.
            val w = t.value.writer?.takeIf { ui.isStaff && it.anonymousForGroupId == null }
            onSendAgain(t.value.prisonerId, letterId, replacesHeld, w?.id, w?.name)
          },
        )
      }
      thread?.let { t ->
        val writer = t.writer
        Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // A group records what came back from the prisoner, on any thread it can see.
          if (ui.isStaff && writer != null) ExtendedFloatingActionButton(
            onClick = { onRecordReply(t.prisonerId, writer.id) },
            icon = { Text("←", modifier = Modifier.clearAndSetSemantics { }) }, text = { Text(stringResource(R.string.action_record_reply)) },
            containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary,
          )
          // A writer writes in their own thread; a group only for writers it manages or as its anonymous writer.
          val groupMayWrite = ui.isStaff && writer?.canBeWrittenForBy(ui.staffGroupId) == true
          if (!ui.isStaff || groupMayWrite) ExtendedFloatingActionButton(
            onClick = { if (groupMayWrite && writer != null && writer.anonymousForGroupId == null) onGroupWrite(t.prisonerId, writer.id, writer.name) else onWrite(t.prisonerId) },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            text = { Text(stringResource(R.string.action_write)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          )
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
  }

  ui.relayQuestion?.let { q ->
    AlertDialog(
      onDismissRequest = viewModel::relayQuestionDismissed,
      title = { Text(stringResource(R.string.relay_question_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.relay_question_text, q.facilityName), style = MaterialTheme.typography.bodyMedium)
          q.options.forEach { g -> TextButton(onClick = { viewModel.chooseRelay(g) }, modifier = Modifier.fillMaxWidth().testTag("relay-${g.id}")) { Text(g.name) } }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = viewModel::relayQuestionDismissed) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  confirmDelete?.let { id ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text(stringResource(R.string.delete_letter_title)) },
      text = { Text(stringResource(R.string.delete_letter_text)) },
      confirmButton = { TextButton(onClick = { viewModel.delete(id); confirmDelete = null }) { Text(stringResource(R.string.action_delete)) } },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_keep_it)) } },
    )
  }
}

@Composable
private fun ThreadBody(
  thread: Thread,
  retentionDays: Int?,
  busyMessageId: Int?,
  onPrisoner: (Int) -> Unit,
  onOpen: (me.paxana.abcmailbox.domain.Attachment) -> Unit,
  mayChange: Boolean,
  onEdit: (Int) -> Unit,
  onDelete: (Int) -> Unit,
  showWriter: Boolean = false,
  onSendAgain: (letterId: Int, replacesHeld: Boolean) -> Unit = { _, _ -> },
  onChooseRelay: (letterId: Int) -> Unit = {},
) {
  // A reply that arrives while the conversation is open lands at the bottom, possibly off screen. Go to it, as a
  // messaging app would, but only when the conversation grew while it was showing: the first load, a deletion
  // and an ordinary reload leave the reader where they were. (`remember` without a key survives recomposition,
  // so `seen` is the count from the previous time round.)
  val listState = androidx.compose.foundation.lazy.rememberLazyListState()
  var seen by remember { mutableIntStateOf(thread.letters.size) }
  LaunchedEffect(thread.letters.size) {
    if (thread.letters.size > seen) listState.animateScrollToItem(thread.letters.size) // index 0 is the header, so this is the last letter
    seen = thread.letters.size
  }
  LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 170.dp)) {
    item("header") {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        thread.prisoner?.let { p ->
          TextButton(onClick = { onPrisoner(p.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(p.name + (p.facility?.let { " · ${it.name}" } ?: ""), color = MaterialTheme.colorScheme.secondary)
          }
        }
        // Whose thread it is matters to a group member, who sees many people's. A writer knows it is theirs.
        if (showWriter) thread.writer?.let { w -> Text(stringResource(R.string.writer_named, w.label(rememberStrings())), style = MaterialTheme.typography.bodyMedium) }
        val sent = thread.letters.count { !it.fromPrisoner }
        val received = thread.letters.size - sent
        Text(stringResource(R.string.thread_counts, pluralStringResource(R.plurals.thread_count_letters, thread.letters.size, thread.letters.size), sent, received), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retentionDays?.let { d ->
          Text(
            if (d == 0) stringResource(R.string.retention_forever) else pluralStringResource(R.plurals.retention_days, d, d),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      HorizontalDivider()
    }
    if (thread.letters.isEmpty()) {
      item("empty") { Text(stringResource(R.string.thread_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
    }
    items(thread.letters, key = { it.id }) { letter ->
      LetterCard(letter, answered = letter.repliesToId?.let { id -> thread.letters.find { it.id == id } }, busy = busyMessageId == letter.id, mayChange = mayChange, onOpen = onOpen, onEdit = { onEdit(letter.id) }, onDelete = { onDelete(letter.id) }, onSendAgain = { onSendAgain(letter.id, letter.heldReason == HeldReason.RESEAL_NEEDED) }, onChooseRelay = { onChooseRelay(letter.id) })
      HorizontalDivider()
    }
  }
}

@Composable
private fun LetterCard(letter: Letter, answered: Letter? = null, busy: Boolean, mayChange: Boolean, onOpen: (me.paxana.abcmailbox.domain.Attachment) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onSendAgain: () -> Unit = {}, onChooseRelay: () -> Unit = {}) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        stringResource(if (letter.fromPrisoner) R.string.letter_received else R.string.letter_sent),
        modifier = Modifier.spokenWithoutArrows(stringResource(if (letter.fromPrisoner) R.string.letter_received else R.string.letter_sent)),
        style = MaterialTheme.typography.titleMedium,
        color = if (letter.fromPrisoner) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
      )
      letter.createdAt?.let { Text(it.longDate(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Box(Modifier.weight(1f))
      // A held letter is still "queued" to the server. To its writer that word would be a lie: nothing is coming for it.
      if (letter.isHeld) Tag(stringResource(R.string.status_held)) else StatusChip(letter.status)
    }
    // API PR #120: the number at the foot of a sent letter, and which letter a reply answers (matched by that number).
    if (!letter.fromPrisoner) letter.replyReference?.let { Text(stringResource(R.string.letter_reference, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("letter-reference")) }
    if (letter.fromPrisoner && letter.repliesToId != null) {
      Text(
        answered?.createdAt?.let { stringResource(R.string.reply_answers_letter, it.longDate()) } ?: stringResource(R.string.reply_answers_gone_letter),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("reply-answers"),
      )
    }
    if (letter.locked) {
      Text(stringResource(R.string.letter_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (letter.body.isNotBlank()) {
      Text(letter.body, style = MaterialTheme.typography.bodyLarge)
    }
    letter.relayNote?.let {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Text(stringResource(R.string.label_note_to_relay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(it, style = MaterialTheme.typography.bodyMedium)
      }
    }
    letter.attachments.forEach { a -> AttachmentRow(a.name, a.sizeLabel, onOpen = { onOpen(a) }) }
    val statusLine = when (letter.status) {
      LetterStatus.QUEUED -> when {
        letter.relayGroupName != null -> stringResource(R.string.status_waiting_named, letter.relayGroupName)
        letter.relayGroupId != null -> stringResource(R.string.status_waiting_relay)
        else -> stringResource(R.string.status_queued_no_relay)
      }
      // Whole sentences per case, not pieces glued together: word order differs between languages.
      LetterStatus.PRINTED -> (letter.relayGroupName ?: stringResource(R.string.the_relay_group)).let { who -> letter.statusChangedAt?.let { stringResource(R.string.status_printed_by_on, who, it.longDate()) } ?: stringResource(R.string.status_printed_by, who) }
      LetterStatus.MAILED -> (letter.relayGroupName ?: stringResource(R.string.the_relay_group)).let { who -> letter.statusChangedAt?.let { stringResource(R.string.status_mailed_by_on, who, it.longDate()) } ?: stringResource(R.string.status_mailed_by, who) }
      LetterStatus.RECEIVED -> stringResource(R.string.status_recorded_by_group)
      LetterStatus.RETURNED -> letter.returnedAt?.let { stringResource(R.string.status_returned_on, it.longDate()) } ?: stringResource(R.string.status_returned_undated)
      LetterStatus.UNKNOWN -> ""
    }
    if (statusLine.isNotBlank() && !letter.isHeld) Text(statusLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (letter.status == LetterStatus.RETURNED) ReturnedNotice(letter, mayChange = mayChange, busy = busy, onSendAgain = onSendAgain)
    letter.heldReason?.takeIf { letter.isHeld }?.let { HeldNotice(it, mayChange = mayChange, busy = busy, canResend = !letter.locked, onChoose = onChooseRelay, onSendAgain = onSendAgain) }
    if (letter.canEdit && !letter.locked && mayChange) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.action_edit)) }
        TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

/**
 * A letter that came back: why (the mail room's claim, said as a claim), what the group wrote down about the
 * envelope, what can be done, and whether it already has been. Worded as the iOS app words it.
 */
@Composable
private fun ReturnedNotice(letter: Letter, mayChange: Boolean, busy: Boolean, onSendAgain: () -> Unit) {
  NoticeBox {
    val reason = letter.returnReason ?: ReturnReason.UNKNOWN
    Text(stringResource(reason.labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    // Labelled as the group's words about the envelope: not the app's opinion, and not the prison's.
    letter.returnNote?.let { note ->
      Text(stringResource(R.string.return_note_from, letter.relayGroupName ?: stringResource(R.string.the_relay_group)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      androidx.compose.foundation.text.selection.SelectionContainer { Text(note, style = MaterialTheme.typography.bodyMedium) }
    }
    val again = letter.resentAs.lastOrNull()
    if (again != null) {
      val state = stringResource(again.status.labelRes).lowercase()
      Text(again.at?.let { stringResource(R.string.return_sent_again_on, it.longDate(), state) } ?: stringResource(R.string.return_sent_again, state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (mayChange && !letter.fromPrisoner) {
      Text(stringResource(reason.adviceRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (!letter.locked) OutlinedButton(onClick = onSendAgain, enabled = !busy, modifier = Modifier.testTag("send-again")) { Text(stringResource(R.string.action_send_again)) }
    }
  }
}

/**
 * A queued letter that is going nowhere until somebody decides. Each reason names who, and offers the way out
 * that is the writer's to take. [mayChange] is false for a group member looking at a letter that is not theirs
 * to change: they read "the writer", not "you", and are offered nothing.
 */
@Composable
private fun HeldNotice(reason: HeldReason, mayChange: Boolean, busy: Boolean, canResend: Boolean, onChoose: () -> Unit, onSendAgain: () -> Unit) {
  NoticeBox {
    Text(stringResource(when (reason) {
      HeldReason.CHOOSE_RELAY -> if (mayChange) R.string.held_choose_relay else R.string.held_choose_relay_writer
      HeldReason.RESEAL_NEEDED -> if (mayChange) R.string.held_reseal_needed else R.string.held_reseal_needed_writer
      HeldReason.PRISONER_FREE -> if (mayChange) R.string.held_prisoner_free else R.string.held_prisoner_free_writer
      HeldReason.OTHER -> R.string.held_other
    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    if (mayChange) when (reason) {
      HeldReason.CHOOSE_RELAY -> OutlinedButton(onClick = onChoose, enabled = !busy, modifier = Modifier.testTag("held-choose")) { Text(stringResource(R.string.action_choose_who_mails)) }
      HeldReason.RESEAL_NEEDED -> if (canResend) OutlinedButton(onClick = onSendAgain, enabled = !busy, modifier = Modifier.testTag("held-resend")) { Text(stringResource(R.string.action_send_again)) }
      // Freed: nothing to press here. Deleting it is the ordinary Delete below; printing it anyway is the group's decision.
      HeldReason.PRISONER_FREE, HeldReason.OTHER -> Unit
    }
  }
}

@Composable
private fun NoticeBox(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
  )
}

@Composable
fun StatusChip(status: LetterStatus) {
  Tag(stringResource(status.labelRes))
}

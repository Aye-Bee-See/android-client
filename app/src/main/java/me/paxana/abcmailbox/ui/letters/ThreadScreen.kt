package me.paxana.abcmailbox.ui.letters

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
  viewModel: ThreadViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  var confirmDelete by remember { mutableStateOf<Int?>(null) }

  LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }
  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  LaunchedEffect(ui.openFile) {
    ui.openFile?.let { (file, mime) ->
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
      val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { snackbar.showSnackbar("No app on this phone can open ${mime}.") }
      viewModel.fileOpened()
    }
  }

  val thread = (ui.thread as? Loadable.Loaded)?.value
  DetailScaffold(title = thread?.title ?: "Conversation", onBack = onBack) { padding ->
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
          onEdit = { onEdit(t.value.prisonerId, it) },
          onDelete = { confirmDelete = it },
        )
      }
      thread?.let {
        ExtendedFloatingActionButton(
          onClick = { onWrite(it.prisonerId) },
          icon = { Icon(Icons.Default.Edit, contentDescription = null) },
          text = { Text("Write") },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
  }

  confirmDelete?.let { id ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("Delete this letter?") },
      text = { Text("It has not been printed yet, so it can still be withdrawn. This cannot be undone.") },
      confirmButton = { TextButton(onClick = { viewModel.delete(id); confirmDelete = null }) { Text("Delete") } },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep it") } },
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
  onEdit: (Int) -> Unit,
  onDelete: (Int) -> Unit,
) {
  LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
    item("header") {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        thread.prisoner?.let { p ->
          TextButton(onClick = { onPrisoner(p.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(p.name + (p.facility?.let { " · ${it.name}" } ?: ""), color = MaterialTheme.colorScheme.secondary)
          }
        }
        val sent = thread.letters.count { !it.fromPrisoner }
        val received = thread.letters.size - sent
        Text("${thread.letters.size} letters · $sent sent · $received received", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retentionDays?.let { d ->
          Text(
            if (d == 0) "Mailed letters are kept until you delete them." else "Mailed letters and replies are removed after $d days unless you keep them; older ones may already be gone.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      HorizontalDivider()
    }
    if (thread.letters.isEmpty()) {
      item("empty") { Text("No letters in this conversation yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
    }
    items(thread.letters, key = { it.id }) { letter ->
      LetterCard(letter, busy = busyMessageId == letter.id, onOpen = onOpen, onEdit = { onEdit(letter.id) }, onDelete = { onDelete(letter.id) })
      HorizontalDivider()
    }
  }
}

@Composable
private fun LetterCard(letter: Letter, busy: Boolean, onOpen: (me.paxana.abcmailbox.domain.Attachment) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        if (letter.fromPrisoner) "← Received" else "→ Sent",
        style = MaterialTheme.typography.titleMedium,
        color = if (letter.fromPrisoner) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
      )
      letter.createdAt?.let { Text(it.longDate(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Box(Modifier.weight(1f))
      StatusChip(letter.status)
    }
    if (letter.locked) {
      Text("🔒 This letter is encrypted and this device does not hold a key that opens it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (letter.body.isNotBlank()) {
      Text(letter.body, style = MaterialTheme.typography.bodyLarge)
    }
    letter.relayNote?.let {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Text("NOTE TO RELAY GROUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(it, style = MaterialTheme.typography.bodyMedium)
      }
    }
    letter.attachments.forEach { a ->
      Row(
        Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("📎", style = MaterialTheme.typography.bodyLarge)
        Text(a.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        Text(a.sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    val statusLine = when (letter.status) {
      LetterStatus.QUEUED -> when {
        letter.relayGroupName != null -> "Waiting for ${letter.relayGroupName} to print it"
        letter.relayGroupId != null -> "Waiting for the relay group to print it"
        else -> "Queued. No relay group is assigned to this facility yet."
      }
      LetterStatus.PRINTED -> "Printed by ${letter.relayGroupName ?: "the relay group"}" + (letter.statusChangedAt?.let { " on ${it.longDate()}" } ?: "")
      LetterStatus.MAILED -> "Mailed by ${letter.relayGroupName ?: "the relay group"}" + (letter.statusChangedAt?.let { " on ${it.longDate()}" } ?: "")
      LetterStatus.RECEIVED -> "Recorded by a support group"
      LetterStatus.UNKNOWN -> ""
    }
    if (statusLine.isNotBlank()) Text(statusLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (letter.canEdit && !letter.locked) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
        TextButton(onClick = onDelete, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
fun StatusChip(status: LetterStatus) {
  Tag(status.label)
}

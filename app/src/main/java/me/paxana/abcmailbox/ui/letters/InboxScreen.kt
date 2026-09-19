package me.paxana.abcmailbox.ui.letters

import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.asHeading
import me.paxana.abcmailbox.ui.common.ErrorText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.paging.compose.collectAsLazyPagingItems
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.Thread
import me.paxana.abcmailbox.ui.common.PagedList
import me.paxana.abcmailbox.ui.common.RecordRow
import me.paxana.abcmailbox.ui.common.shortDate

/** After `inbox-writer.html`: one row per conversation, newest activity first (server order). */
@Composable
fun InboxScreen(
  sessionState: SessionState,
  keysLocked: Boolean,
  onSignIn: () -> Unit,
  onThread: (Int) -> Unit,
  onNewLetter: () -> Unit,
  onQueueLetter: (Int) -> Unit = {},
  onAddWriter: () -> Unit = {},
  onGroupLetter: (writerId: Int?, writerName: String?) -> Unit = { _, _ -> },
  onHandoff: (me.paxana.abcmailbox.domain.ManagedWriter) -> Unit = {},
  onGroupKey: () -> Unit = {},
  onEditQueued: (me.paxana.abcmailbox.data.repo.OutboxItem) -> Unit = {},
) {
  Column(Modifier.fillMaxSize()) {
    Text(stringResource(R.string.title_inbox), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading().padding(horizontal = 20.dp, vertical = 16.dp))
    when (sessionState) {
      SessionState.Loading -> Unit
      SessionState.SignedOut -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.inbox_signed_out))
        Button(onClick = onSignIn) { Text(stringResource(R.string.action_sign_in)) }
      }
      is SessionState.SignedIn -> {
        // Unsent letters come first: they are the one thing here that may need the writer.
        OutboxSection(onEdit = onEditQueued)
        when {
        keysLocked -> UnlockPrompt()
        // Group members get the queue, the conversations they can see, and their writers.
        sessionState.session.user.isStaff -> me.paxana.abcmailbox.ui.group.GroupInbox(
          conversations = { SignedInInbox(sessionState.session.user.displayName, onThread, onNewLetter = null) },
          onLetter = onQueueLetter, onAddWriter = onAddWriter, onNewLetter = onGroupLetter, onHandoff = onHandoff, onGroupKey = onGroupKey,
        )
        else -> SignedInInbox(sessionState.session.user.displayName, onThread, onNewLetter)
        }
      }
    }
  }
}

@Composable
private fun SignedInInbox(name: String, onThread: (Int) -> Unit, onNewLetter: (() -> Unit)?, viewModel: InboxViewModel = hiltViewModel()) {
  val items = viewModel.threads.collectAsLazyPagingItems()
  // Coming back from compose or a thread: reload so new letters and status changes show.
  LifecycleResumeEffect(Unit) { items.refresh(); onPauseOrDispose { } }
  Box(Modifier.fillMaxSize()) {
    PagedList(
      items = items,
      emptyText = if (onNewLetter != null) stringResource(R.string.inbox_empty_writer) else stringResource(R.string.inbox_empty_group),
      header = {
        item("who") {
          Text(stringResource(R.string.signed_in_as, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }
      },
    ) { t -> ThreadRow(t, onClick = { onThread(t.id) }, showWriter = onNewLetter == null) }
    if (onNewLetter != null) ExtendedFloatingActionButton(
      onClick = onNewLetter,
      icon = { Icon(Icons.Default.Edit, contentDescription = null) },
      text = { Text(stringResource(R.string.action_new_letter)) },
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
    )
  }
}

@Composable
fun ThreadRow(t: Thread, onClick: () -> Unit, showWriter: Boolean = false) {
  val last = t.lastMessage
  val direction = when {
    last == null -> stringResource(R.string.thread_no_letters)
    last.fromPrisoner -> stringResource(R.string.thread_last_received)
    else -> stringResource(R.string.thread_last_sent, stringResource(last.status.labelRes))
  }
  RecordRow(
    title = t.title(rememberStrings()),
    secondary = listOfNotNull(t.writer?.takeIf { showWriter }?.let { stringResource(R.string.writer_named, it.label(rememberStrings())) }, t.prisoner?.facility?.let { f -> f.name + (f.country?.let { ", $it" } ?: "") }).joinToString(" · ").ifBlank { null },
    subtitle = direction + (t.lastActivity?.let { " · ${it.shortDate()}" } ?: ""),
    onClick = onClick,
  )
}

/** End-to-end server, signed in, but this device does not hold the private key (restored phone, cleared data). */
@Composable
private fun UnlockPrompt(viewModel: UnlockViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.unlock_explained))
    OutlinedTextField(
      value = ui.password, onValueChange = viewModel::onPassword, label = { Text(stringResource(R.string.label_password)) }, singleLine = true, enabled = !ui.busy,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { viewModel.unlock() }),
      modifier = Modifier.fillMaxWidth(),
    )
    ui.error?.let { ErrorText(it) }
    Button(onClick = viewModel::unlock, enabled = !ui.busy && ui.password.isNotEmpty()) { Text(if (ui.busy) stringResource(R.string.action_unlocking) else stringResource(R.string.action_unlock)) }
  }
}

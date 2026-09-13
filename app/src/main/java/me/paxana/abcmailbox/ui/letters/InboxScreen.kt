package me.paxana.abcmailbox.ui.letters

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
  onSignIn: () -> Unit,
  onThread: (Int) -> Unit,
  onNewLetter: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    Text("Inbox", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    when (sessionState) {
      SessionState.Loading -> Unit
      SessionState.SignedOut -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in to see your conversations and write letters.")
        Button(onClick = onSignIn) { Text("Sign in") }
      }
      is SessionState.SignedIn -> SignedInInbox(sessionState.session.user.displayName, onThread, onNewLetter)
    }
  }
}

@Composable
private fun SignedInInbox(name: String, onThread: (Int) -> Unit, onNewLetter: () -> Unit, viewModel: InboxViewModel = hiltViewModel()) {
  val items = viewModel.threads.collectAsLazyPagingItems()
  // Coming back from compose or a thread: reload so new letters and status changes show.
  LifecycleResumeEffect(Unit) { items.refresh(); onPauseOrDispose { } }
  Box(Modifier.fillMaxSize()) {
    PagedList(
      items = items,
      emptyText = "No conversations yet. Start one with the button below.",
      header = {
        item("who") {
          Text("Signed in as $name", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }
      },
    ) { t -> ThreadRow(t, onClick = { onThread(t.id) }) }
    ExtendedFloatingActionButton(
      onClick = onNewLetter,
      icon = { Icon(Icons.Default.Edit, contentDescription = null) },
      text = { Text("New letter") },
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
    )
  }
}

@Composable
fun ThreadRow(t: Thread, onClick: () -> Unit) {
  val last = t.lastMessage
  val direction = when {
    last == null -> "No letters yet"
    last.fromPrisoner -> "← Letter received"
    else -> "→ Letter sent · ${last.status.label}"
  }
  RecordRow(
    title = t.title,
    secondary = t.prisoner?.facility?.let { f -> f.name + (f.country?.let { ", $it" } ?: "") },
    subtitle = direction + (t.lastActivity?.let { " · ${it.shortDate()}" } ?: ""),
    onClick = onClick,
  )
}

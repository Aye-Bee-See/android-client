package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.ui.common.ErrorText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.data.crypto.GroupKeyState
import me.paxana.abcmailbox.domain.GroupMember
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.directory.Loadable

/**
 * End-to-end servers only: tells a group member where they stand with the
 * group's key, above the inbox tabs. Says nothing in server mode. Each state
 * is a normal situation with a next step, so none is styled as an error.
 */
@Composable
fun GroupKeyBanner(onMembers: () -> Unit, viewModel: GroupKeyViewModel = hiltViewModel()) {
  val state by viewModel.keyState.collectAsStateWithLifecycle()
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  // Another member may have acted since this screen was last shown.
  LifecycleResumeEffect(Unit) { if (state !is GroupKeyState.Ready) viewModel.refresh(); onPauseOrDispose { } }

  when (val s = state) {
    GroupKeyState.NotNeeded -> Unit
    is GroupKeyState.Ready -> Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("End-to-end encrypted · group key open (version ${s.key.version})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      TextButton(onClick = onMembers) { Text("Members") }
    }
    is GroupKeyState.NotSetUp -> Notice(
      title = "Your group has no encryption key yet",
      body = "Letters on this server are encrypted so that only the writer and the relay group can read them. Your group needs a key of its own before anyone can send it a letter. " +
        "It is made on this phone, once, and you then hand it to the other members.",
    ) {
      Button(onClick = viewModel::setUp, enabled = !ui.busy) { Text(if (ui.busy) "Setting up…" else "Set up the group key") }
      ui.error?.let { ErrorText(it, style = MaterialTheme.typography.bodySmall) }
    }
    is GroupKeyState.NotHeld -> Notice(
      title = "You have not been given the group key yet",
      body = "Until a member who holds the key hands it to you, letters sent to your group stay locked on this phone. Ask them to open Inbox, Members, and choose your name.",
    ) { OutlinedButton(onClick = viewModel::refresh) { Text("Check again") } }
    GroupKeyState.Locked -> Notice(
      title = "Your letters are locked on this device",
      body = "Unlock them with your password from the Account tab; the group key opens with your own.",
    ) {}
    is GroupKeyState.Failed -> Notice(title = "The group key could not be checked", body = s.error.userMessage ?: "No connection to the server.") {
      OutlinedButton(onClick = viewModel::refresh) { Text("Try again") }
    }
  }
}

@Composable
private fun Notice(title: String, body: String, actions: @Composable () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(body, style = MaterialTheme.typography.bodyMedium)
      actions()
    }
  }
}

/** Who in the group can read its letters, and handing the key to those who cannot yet. */
@Composable
fun GroupKeyScreen(onBack: () -> Unit, viewModel: GroupKeyViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  var confirmStop by remember { mutableStateOf<GroupMember?>(null) }
  LaunchedEffect(Unit) { viewModel.loadMembers() }
  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }

  DetailScaffold(title = "Group key", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (val m = ui.members) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(m.error, onRetry = viewModel::loadMembers)
        is Loadable.Loaded -> LazyColumn(Modifier.weight(1f)) {
          item("intro") {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Each member holds their own sealed copy of the group key, so nobody shares a password. Hand the key to a member and they can read and print the group's letters.", style = MaterialTheme.typography.bodyMedium)
              Text("Taking a member off this list stops new copies being given to them. It cannot take back a copy their phone has already opened: if someone should lose access for good, rotate the group key on the website.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              ui.error?.let { ErrorText(it) }
            }
            HorizontalDivider()
          }
          items(m.value, key = { it.id }) { member ->
            MemberRow(member, busy = ui.busyMemberId == member.id, onHand = { viewModel.hand(member) }, onStop = { confirmStop = member })
            HorizontalDivider()
          }
        }
      }
      SnackbarHost(snackbar) { Snackbar(it) }
    }
  }

  confirmStop?.let { member ->
    AlertDialog(
      onDismissRequest = { confirmStop = null },
      title = { Text("Stop handing the key to ${member.name}?") },
      text = { Text("They will not get the group key on their next sign-in. A phone that already opened it keeps it until the group key is rotated.") },
      confirmButton = { TextButton(onClick = { viewModel.stop(member); confirmStop = null }) { Text("Stop") } },
      dismissButton = { TextButton(onClick = { confirmStop = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun MemberRow(member: GroupMember, busy: Boolean, onHand: () -> Unit, onStop: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(member.name + if (member.isMe) " (you)" else "", style = MaterialTheme.typography.titleSmall)
      Text(
        when {
          member.holdsGroupKey -> "Holds the group key"
          !member.hasOwnKey -> "Has not signed in yet, so there is nothing to seal the key to"
          else -> "Cannot read the group's letters yet"
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    when {
      member.isMe -> Unit
      member.holdsGroupKey -> TextButton(onClick = onStop, enabled = !busy) { Text("Stop") }
      member.hasOwnKey -> Button(onClick = onHand, enabled = !busy) { Text(if (busy) "Sealing…" else "Hand key") }
    }
  }
}

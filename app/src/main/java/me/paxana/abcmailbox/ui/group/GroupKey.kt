package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
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
      Text(stringResource(R.string.group_key_open, s.key.version), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      TextButton(onClick = onMembers) { Text(stringResource(R.string.action_members)) }
    }
    is GroupKeyState.NotSetUp -> Notice(
      title = stringResource(R.string.group_key_none_title),
      body = stringResource(R.string.group_key_none_text),
    ) {
      Button(onClick = viewModel::setUp, enabled = !ui.busy) { Text(stringResource(if (ui.busy) R.string.action_setting_up else R.string.action_set_up_group_key)) }
      ui.error?.let { ErrorText(it, style = MaterialTheme.typography.bodySmall) }
    }
    is GroupKeyState.NotHeld -> Notice(
      title = stringResource(R.string.group_key_not_held_title),
      body = stringResource(R.string.group_key_not_held_text),
    ) { OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_check_again)) } }
    GroupKeyState.Locked -> Notice(
      title = stringResource(R.string.group_key_locked_title),
      body = stringResource(R.string.group_key_locked_text),
    ) {}
    is GroupKeyState.Failed -> Notice(title = stringResource(R.string.group_key_failed_title), body = s.error.userMessage ?: stringResource(R.string.error_no_connection)) {
      OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_try_again)) }
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

  DetailScaffold(title = stringResource(R.string.title_group_key), onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (val m = ui.members) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(m.error, onRetry = viewModel::loadMembers)
        is Loadable.Loaded -> LazyColumn(Modifier.weight(1f)) {
          item("intro") {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(stringResource(R.string.members_intro), style = MaterialTheme.typography.bodyMedium)
              Text(stringResource(R.string.members_stop_explained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
      title = { Text(stringResource(R.string.stop_handing_title, member.name)) },
      text = { Text(stringResource(R.string.stop_handing_text)) },
      confirmButton = { TextButton(onClick = { viewModel.stop(member); confirmStop = null }) { Text(stringResource(R.string.action_stop)) } },
      dismissButton = { TextButton(onClick = { confirmStop = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

@Composable
private fun MemberRow(member: GroupMember, busy: Boolean, onHand: () -> Unit, onStop: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(if (member.isMe) stringResource(R.string.member_you, member.name) else member.name, style = MaterialTheme.typography.titleSmall)
      Text(
        when {
          member.holdsGroupKey -> stringResource(R.string.member_holds_key)
          !member.hasOwnKey -> stringResource(R.string.member_never_signed_in)
          else -> stringResource(R.string.member_cannot_read)
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    when {
      member.isMe -> Unit
      member.holdsGroupKey -> TextButton(onClick = onStop, enabled = !busy) { Text(stringResource(R.string.action_stop)) }
      member.hasOwnKey -> Button(onClick = onHand, enabled = !busy) { Text(stringResource(if (busy) R.string.action_sealing else R.string.action_hand_key)) }
    }
  }
}

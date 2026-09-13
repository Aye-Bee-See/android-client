package me.paxana.abcmailbox.ui.letters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.RelayChoice
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.SectionTitle

/**
 * After `new-letter.html`: the facility's rules above the editor, a character
 * and page count, an optional private note to the relay group, attachments,
 * and the relay-group picker only when the facility has more than one.
 */
@Composable
fun ComposeScreen(
  sessionState: SessionState,
  onSignIn: () -> Unit,
  onBack: () -> Unit,
  onSent: (chatId: Int) -> Unit,
  viewModel: ComposeViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::attach) }

  LaunchedEffect(ui.sentChatId) { ui.sentChatId?.let { if (ui.error == null) onSent(it) } }
  LaunchedEffect(ui.draftRestored) { if (ui.draftRestored) { snackbar.showSnackbar("Draft restored."); viewModel.draftNoticeShown() } }

  DetailScaffold(title = if (ui.editing) "Edit letter" else "New letter", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (sessionState is SessionState.SignedOut) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Sign in to write a letter.")
          Button(onClick = onSignIn) { Text("Sign in") }
        }
        return@Column
      }
      if (ui.loading) { LoadingBox(); return@Column }

      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ui.prisoner?.let { p ->
          Text("To: ${p.name}", style = MaterialTheme.typography.titleLarge)
          ui.facility?.let { f -> Text(f.name + f.shortLocation.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        ui.facility?.let { f ->
          SectionTitle("Facility rules · ${f.name}")
          if (f.rules.isEmpty()) Text("No rules recorded for this facility. Confirm with a support group before writing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          f.rules.forEach { r -> Text("• ${r.title}" + (r.description?.let { d -> ": $d" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
        }

        RelaySection(ui.relay, ui.selectedRelay, viewModel::onSelectRelay)

        OutlinedTextField(
          value = ui.body,
          onValueChange = viewModel::onBodyChange,
          placeholder = { Text("Write your letter here. Paragraph breaks will be preserved when printed.") },
          minLines = 8,
          enabled = !ui.sending,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          "${ui.characters} characters · ~${ui.pages} page${if (ui.pages == 1) "" else "s"}",
          style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (ui.showNote) {
          OutlinedTextField(
            value = ui.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text("Note to relay group") },
            placeholder = { Text("Optional. Only your relay group will see this.") },
            minLines = 2,
            enabled = !ui.sending,
            modifier = Modifier.fillMaxWidth(),
          )
          Text("Not sent to the prisoner. Use it for context about the letter or its origin.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          TextButton(onClick = viewModel::onToggleNote) { Text("+ Add a note to relay group") }
        }

        SectionTitle("Attachments")
        ui.attachments.forEach { f ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📎 ${f.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { viewModel.removeAttachment(f) }, enabled = !ui.sending) { Text("Remove") }
          }
        }
        OutlinedButton(onClick = { picker.launch(ATTACHMENT_MIME_TYPES) }, enabled = !ui.sending) { Text("Attach a file") }
        Text("PDF, JPG, PNG, or WebP · max 10 MB. A scan of a handwritten letter works well.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Button(onClick = viewModel::send, enabled = ui.canSend, modifier = Modifier.fillMaxWidth()) {
          Text(ui.progress ?: if (ui.editing) "Save changes" else "Send letter")
        }
        Text(
          "Your letter won't be sent immediately. It goes to your relay group's queue, where they will print and physically mail it on your behalf.",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
      }
      SnackbarHost(snackbar) { Snackbar(it) }
    }
  }
}

@Composable
private fun RelaySection(relay: RelayChoice, selected: Int?, onSelect: (Int?) -> Unit) {
  when (relay) {
    is RelayChoice.Automatic -> Text("Relayed by ${relay.group.name}" + relay.group.location.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
    is RelayChoice.Direct -> Text("Mailed directly to the facility.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    is RelayChoice.Blocked -> AlertBanner("⚠ ${relay.reason}")
    is RelayChoice.Choose -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      SectionTitle("Relay group")
      Text(
        if (relay.required) "This facility only accepts relayed mail. Choose which group to route through." else "${relay.options.size} groups relay to this facility. Choose one, or leave it to the group.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      relay.options.forEach { g ->
        Row(
          Modifier.fillMaxWidth().selectable(selected = selected == g.id, onClick = { onSelect(g.id) }).padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = selected == g.id, onClick = { onSelect(g.id) })
          Column { Text(g.name); g.location.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
      }
      if (!relay.required) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected == null, onClick = { onSelect(null) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = selected == null, onClick = { onSelect(null) })
          Text("Let the network decide")
        }
      }
    }
  }
}

package me.paxana.abcmailbox.ui.letters

import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.ErrorText
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
import me.paxana.abcmailbox.ui.common.MailRulesList
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
  /** No connection: the letter went to the outbox. The shell closes this screen and says so. */
  onQueued: () -> Unit = {},
  viewModel: ComposeViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::attach) }
  val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> viewModel.onPhotoResult(taken) }

  LaunchedEffect(ui.sentChatId) { ui.sentChatId?.let { if (ui.error == null) onSent(it) } }
  LaunchedEffect(ui.queuedOffline) { if (ui.queuedOffline) onQueued() }
  val draftRestored = stringResource(R.string.draft_restored)
  LaunchedEffect(ui.draftRestored) { if (ui.draftRestored) { snackbar.showSnackbar(draftRestored); viewModel.draftNoticeShown() } }

  DetailScaffold(title = when { ui.recordingReply -> stringResource(R.string.title_record_reply); ui.editing -> stringResource(R.string.title_edit_letter); else -> stringResource(R.string.title_new_letter) }, onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (sessionState is SessionState.SignedOut) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.compose_signed_out))
          Button(onClick = onSignIn) { Text(stringResource(R.string.action_sign_in)) }
        }
        return@Column
      }
      if (ui.loading) { LoadingBox(); return@Column }

      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ui.prisoner?.let { p ->
          Text(stringResource(if (ui.recordingReply) R.string.compose_from else R.string.compose_to, p.name), style = MaterialTheme.typography.titleLarge)
          ui.writingAs?.let { Text(stringResource(R.string.compose_writing_as, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
          if (ui.recordingReply) Text(stringResource(R.string.compose_reply_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          ui.facility?.let { f -> Text(f.name + f.shortLocation.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (ui.sendingAgain) Text(stringResource(R.string.compose_sending_again), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)

        if (!ui.recordingReply) ui.facility?.let { f ->
          SectionTitle(stringResource(R.string.compose_rules_for, f.name))
          MailRulesList(f.rules, emptyText = stringResource(R.string.compose_rules_none))
        }

        if (!ui.recordingReply) RelaySection(ui.relay, ui.selectedRelay, viewModel::onSelectRelay)

        OutlinedTextField(
          value = ui.body,
          onValueChange = viewModel::onBodyChange,
          placeholder = { Text(stringResource(if (ui.recordingReply) R.string.compose_placeholder_reply else R.string.compose_placeholder)) },
          minLines = 8,
          enabled = !ui.sending,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          stringResource(R.string.compose_count, pluralStringResource(R.plurals.compose_characters, ui.characters, ui.characters), pluralStringResource(R.plurals.compose_pages, ui.pages, ui.pages)),
          style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // What the rules mean for this particular letter: warnings in red, the rest as notes.
        if (!ui.recordingReply) ui.advice(rememberStrings()).forEach { a ->
          if (a.warning) AlertBanner(stringResource(R.string.warning_prefix, a.text)) else Text(a.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (ui.recordingReply) {
          // No note to a relay group on a reply: it is not being mailed anywhere.
        } else if (ui.showNote) {
          OutlinedTextField(
            value = ui.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.label_note_to_relay_field)) },
            placeholder = { Text(stringResource(R.string.note_placeholder)) },
            minLines = 2,
            enabled = !ui.sending,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(stringResource(R.string.note_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          TextButton(onClick = viewModel::onToggleNote) { Text(stringResource(R.string.action_add_note)) }
        }

        SectionTitle(stringResource(R.string.section_attachments))
        ui.attachments.forEach { f ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.attachment_named, f.name), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { viewModel.removeAttachment(f) }, enabled = !ui.sending) { Text(stringResource(R.string.action_remove)) }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { picker.launch(if (ui.recordingReply) ATTACHMENT_MIME_TYPES else ui.allowedAttachmentTypes) }, enabled = !ui.sending) {
            Text(stringResource(if (!ui.recordingReply && ui.allowedAttachmentTypes.size == 1) R.string.action_attach_pdf else R.string.action_attach_file))
          }
          // The phone's camera is the natural scanner for a handwritten letter or a prisoner's reply.
          if (ui.recordingReply || ui.allowedAttachmentTypes.size > 1) {
            OutlinedButton(onClick = { camera.launch(viewModel.prepareCamera()) }, enabled = !ui.sending) { Text(stringResource(R.string.action_take_photo)) }
          }
        }
        Text(stringResource(R.string.attachments_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ui.error?.let { ErrorText(it) }

        Button(onClick = viewModel::send, enabled = ui.canSend, modifier = Modifier.fillMaxWidth()) {
          Text(ui.progress ?: when { ui.recordingReply -> stringResource(R.string.action_save_reply); ui.editing -> stringResource(R.string.action_save_changes); else -> stringResource(R.string.action_send_letter) })
        }
        if (!ui.recordingReply) Text(
          stringResource(R.string.compose_not_immediate),
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
    is RelayChoice.Automatic -> Text(stringResource(R.string.relayed_by, relay.group.name + relay.group.location.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()), style = MaterialTheme.typography.bodyMedium)
    is RelayChoice.Direct -> Text(stringResource(R.string.mailed_directly), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    is RelayChoice.Blocked -> AlertBanner(stringResource(R.string.warning_prefix, stringResource(R.string.relay_blocked, relay.facilityName)))
    is RelayChoice.Choose -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      SectionTitle(stringResource(R.string.section_relay_group))
      Text(
        if (relay.required) stringResource(R.string.relay_must_choose) else pluralStringResource(R.plurals.relay_may_choose, relay.options.size, relay.options.size),
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
          Text(stringResource(R.string.relay_let_network_decide))
        }
      }
    }
  }
}

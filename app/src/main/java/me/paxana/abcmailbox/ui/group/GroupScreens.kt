package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.SecretCodeText
import me.paxana.abcmailbox.ui.common.AttachmentRow
import me.paxana.abcmailbox.ui.common.ErrorText
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.crypto.SecretCodes
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.QueueItem
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.MailRulesList
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.letters.StatusChip

/** One queued letter as the volunteer at the printer needs it: address, rules, text, and the two status buttons. */
@Composable
fun LetterWorkScreen(onBack: () -> Unit, onThread: (Int) -> Unit, viewModel: LetterWorkViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val strings = rememberStrings() // not context.getString: this follows a language change while the screen is open
  var confirmMailed by remember { mutableStateOf(false) }
  var choosePartner by remember { mutableStateOf(false) }

  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  LaunchedEffect(ui.openFile) {
    ui.openFile?.let { (file, mime) ->
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
      try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
      catch (e: ActivityNotFoundException) { snackbar.showSnackbar(strings.get(R.string.no_app_opens, mime)) }
      viewModel.fileOpened()
    }
  }

  DetailScaffold(title = stringResource(R.string.title_letter_to_print), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = ui.item) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(s.error, onRetry = viewModel::load)
        is Loadable.Loaded -> LetterWorkBody(
          item = s.value, busy = ui.busy,
          onAdvance = { if (s.value.letter.status == LetterStatus.PRINTED) confirmMailed = true else viewModel.advance() },
          onPrint = { PrintLetter.print(context, strings.get(R.string.print_job_name, s.value.prisoner?.name ?: strings.get(R.string.print_job_prisoner)), s.value.letter.body) },
          onOpen = viewModel::open,
          onThread = { s.value.letter.threadId?.let(onThread) },
          canShare = ui.partners.isNotEmpty() && !s.value.letter.locked,
          onShare = { choosePartner = true },
        )
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
  }

  if (choosePartner) AlertDialog(
    onDismissRequest = { choosePartner = false },
    title = { Text(stringResource(R.string.share_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.share_text), style = MaterialTheme.typography.bodyMedium)
        ui.partners.forEach { g -> TextButton(onClick = { choosePartner = false; viewModel.share(g) }, modifier = Modifier.fillMaxWidth()) { Text(g.name) } }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = { choosePartner = false }) { Text(stringResource(R.string.action_cancel)) } },
  )

  if (confirmMailed) AlertDialog(
    onDismissRequest = { confirmMailed = false },
    title = { Text(stringResource(R.string.mark_mailed_title)) },
    text = { Text(stringResource(R.string.mark_mailed_text)) },
    confirmButton = { TextButton(onClick = { confirmMailed = false; viewModel.advance() }) { Text(stringResource(R.string.action_in_the_post)) } },
    dismissButton = { TextButton(onClick = { confirmMailed = false }) { Text(stringResource(R.string.action_not_yet)) } },
  )
}

@Composable
private fun LetterWorkBody(
  item: QueueItem, busy: Boolean, onAdvance: () -> Unit, onPrint: () -> Unit, onOpen: (me.paxana.abcmailbox.domain.Attachment) -> Unit, onThread: () -> Unit,
  canShare: Boolean = false, onShare: () -> Unit = {},
) {
  val letter = item.letter
  val p = item.prisoner
  Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusChip(letter.status)
      letter.createdAt?.let { Text(stringResource(R.string.written_on, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    // The envelope: name, number, facility, address. Selectable so it can be copied to a label app.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
      Text(stringResource(R.string.label_address_envelope), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
          Text((p?.birthName ?: p?.name ?: stringResource(R.string.prisoner_numbered, letter.prisonerId ?: 0)).let { name -> p?.inmateId?.let { stringResource(R.string.name_with_number, name, it) } ?: name }, style = MaterialTheme.typography.titleMedium)
          p?.facility?.let { f ->
            Text(f.name)
            f.addressLines.forEach { Text(it) }
            f.country?.let { Text(it) }
          }
        }
      }
      if (p?.birthName != null) Text(stringResource(R.string.goes_by, p.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }

    letter.relayNote?.let { AlertBanner(stringResource(R.string.note_from_writer, it)) }

    p?.facility?.let { f ->
      SectionTitle(stringResource(R.string.rules_for, f.name))
      MailRulesList(f.rules, emptyText = stringResource(R.string.rules_none_check))
    }

    SectionTitle(stringResource(R.string.section_the_letter))
    if (letter.locked) Text(stringResource(R.string.letter_locked), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else androidx.compose.foundation.text.selection.SelectionContainer { Text(letter.body.ifBlank { stringResource(R.string.letter_no_text) }, style = MaterialTheme.typography.bodyLarge) }

    letter.attachments.forEach { a -> AttachmentRow(a.name, a.sizeLabel, onOpen = { onOpen(a) }) }

    if (!letter.locked && letter.body.isNotBlank()) OutlinedButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_print_letter)) }
    when (letter.status) {
      LetterStatus.QUEUED -> Button(onClick = onAdvance, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("advance")) { Text(stringResource(R.string.action_mark_printed)) }
      LetterStatus.PRINTED -> Button(onClick = onAdvance, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("advance")) { Text(stringResource(R.string.action_mark_mailed)) }
      LetterStatus.MAILED -> Text(letter.statusChangedAt?.let { stringResource(R.string.mailed_done_on, it.longDate()) } ?: stringResource(R.string.mailed_done), color = MaterialTheme.colorScheme.onSurfaceVariant)
      else -> Unit
    }
    // End-to-end only, and only where the facility has another relay group: the server permits no other readers.
    if (canShare && letter.status != LetterStatus.MAILED) OutlinedButton(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_title)) }
    TextButton(onClick = onThread) { Text(stringResource(R.string.action_open_conversation)) }
  }
}

/** After `create-writer.html`. */
@Composable
fun AddWriterScreen(onBack: () -> Unit, onDone: (ManagedWriter, thenWrite: Boolean) -> Unit, viewModel: AddWriterViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(ui.created) { ui.created?.let { onDone(it, ui.thenWrite) } }
  DetailScaffold(title = stringResource(R.string.title_add_writer), onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.add_writer_intro), style = MaterialTheme.typography.bodyLarge)
      Text(stringResource(R.string.add_writer_anonymous_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(ui.name, viewModel::onName, label = { Text(stringResource(R.string.label_name)) }, supportingText = { Text(stringResource(R.string.add_writer_name_help)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("writer-name"))
      OutlinedTextField(ui.email, viewModel::onEmail, label = { Text(stringResource(R.string.label_email_optional)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
      OutlinedTextField(ui.note, viewModel::onNote, label = { Text(stringResource(R.string.label_internal_note)) }, supportingText = { Text(stringResource(R.string.internal_note_help)) }, minLines = 2, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
      ui.error?.let { ErrorText(it) }
      Button(onClick = { viewModel.submit(thenWrite = true) }, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_add_writer_and_write)) }
      OutlinedButton(onClick = { viewModel.submit(thenWrite = false) }, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("writer-add")) { Text(stringResource(R.string.action_add_writer)) }
    }
  }
}

/**
 * After `handoff.html`, with the copy corrected to what the API does: on claim
 * the group keeps the letters it relayed (it needs them for records and
 * reprints) and loses the rest of the writer's threads and the account.
 */
@Composable
fun HandoffScreen(onBack: () -> Unit, viewModel: HandoffViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val clipboard = LocalClipboardManager.current
  DetailScaffold(title = stringResource(R.string.title_hand_off), onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.writer_named, ui.writerName), style = MaterialTheme.typography.titleLarge)
      Text(stringResource(R.string.handoff_intro, ui.writerName), style = MaterialTheme.typography.bodyLarge)
      listOf(
        stringResource(R.string.handoff_point_1),
        stringResource(R.string.handoff_point_2),
        stringResource(R.string.handoff_point_3),
      ).forEach { Text(stringResource(R.string.bullet, it), style = MaterialTheme.typography.bodyMedium) }

      val token = ui.token
      if (token == null) {
        if (ui.revoked) Text(stringResource(R.string.token_revoked), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = viewModel::generate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("generate")) { Text(stringResource(if (ui.busy) R.string.action_working else R.string.action_generate_token)) }
        // This screen cannot see an earlier token, only cancel it; once that is done there is nothing left to revoke.
        if (!ui.revoked) OutlinedButton(onClick = viewModel::revoke, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_revoke_current)) }
      } else {
        SecretCodeText(token.token)
        token.expiresAt?.let { Text(stringResource(R.string.token_expires_once, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SecretCodes.pretty(token.token))) }) { Text(stringResource(R.string.action_copy)) }
        AlertBanner(stringResource(R.string.token_give_in_person, ui.writerName))
        OutlinedButton(onClick = viewModel::generate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_regenerate)) }
        TextButton(onClick = viewModel::revoke, enabled = !ui.busy) { Text(stringResource(R.string.action_revoke), color = MaterialTheme.colorScheme.error) }
      }
      ui.error?.let { ErrorText(it) }
    }
  }
}

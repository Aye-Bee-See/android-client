package me.paxana.abcmailbox.ui.group

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
  var confirmMailed by remember { mutableStateOf(false) }
  var choosePartner by remember { mutableStateOf(false) }

  LaunchedEffect(ui.notice) { ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() } }
  LaunchedEffect(ui.openFile) {
    ui.openFile?.let { (file, mime) ->
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
      try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
      catch (e: ActivityNotFoundException) { snackbar.showSnackbar("No app on this phone can open $mime.") }
      viewModel.fileOpened()
    }
  }

  DetailScaffold(title = "Letter to print", onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = ui.item) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(s.error, onRetry = viewModel::load)
        is Loadable.Loaded -> LetterWorkBody(
          item = s.value, busy = ui.busy,
          onAdvance = { if (s.value.letter.status == LetterStatus.PRINTED) confirmMailed = true else viewModel.advance() },
          onPrint = { PrintLetter.print(context, "Letter to ${s.value.prisoner?.name ?: "prisoner"}", s.value.letter.body) },
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
    title = { Text("Share with a partner group") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("They will be able to read and print this letter. It stays in your queue: your group still marks it printed and mailed, so agree between you who posts it.", style = MaterialTheme.typography.bodyMedium)
        ui.partners.forEach { g -> TextButton(onClick = { choosePartner = false; viewModel.share(g) }, modifier = Modifier.fillMaxWidth()) { Text(g.name) } }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = { choosePartner = false }) { Text("Cancel") } },
  )

  if (confirmMailed) AlertDialog(
    onDismissRequest = { confirmMailed = false },
    title = { Text("Mark as mailed?") },
    text = { Text("Do this once the letter is actually in the post. The writer will see it as mailed, and it cannot be moved back.") },
    confirmButton = { TextButton(onClick = { confirmMailed = false; viewModel.advance() }) { Text("It is in the post") } },
    dismissButton = { TextButton(onClick = { confirmMailed = false }) { Text("Not yet") } },
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
      letter.createdAt?.let { Text("Written ${it.longDate()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    // The envelope: name, number, facility, address. Selectable so it can be copied to a label app.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
      Text("ADDRESS THE ENVELOPE TO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
          Text((p?.birthName ?: p?.name ?: "Prisoner #${letter.prisonerId}") + (p?.inmateId?.let { " #$it" } ?: ""), style = MaterialTheme.typography.titleMedium)
          p?.facility?.let { f ->
            Text(f.name)
            f.addressLines.forEach { Text(it) }
            f.country?.let { Text(it) }
          }
        }
      }
      if (p?.birthName != null) Text("Goes by ${p.name}. Facilities usually need the legal name on the envelope.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }

    letter.relayNote?.let { AlertBanner("Note from the writer: $it") }

    p?.facility?.let { f ->
      SectionTitle("Mail rules · ${f.name}")
      MailRulesList(f.rules, emptyText = "No rules recorded. Check before mailing.")
    }

    SectionTitle("The letter")
    if (letter.locked) Text("🔒 This letter is encrypted and this device does not hold a key that opens it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else androidx.compose.foundation.text.selection.SelectionContainer { Text(letter.body.ifBlank { "(No text. See the attached file.)" }, style = MaterialTheme.typography.bodyLarge) }

    letter.attachments.forEach { a ->
      Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("📎"); Text(a.name, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f)); Text(a.sizeLabel, style = MaterialTheme.typography.labelSmall)
      }
    }

    if (!letter.locked && letter.body.isNotBlank()) OutlinedButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) { Text("Print the letter") }
    when (letter.status) {
      LetterStatus.QUEUED -> Button(onClick = onAdvance, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("advance")) { Text("Mark as printed") }
      LetterStatus.PRINTED -> Button(onClick = onAdvance, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("advance")) { Text("Mark as mailed") }
      LetterStatus.MAILED -> Text("Mailed" + (letter.statusChangedAt?.let { " on ${it.longDate()}" } ?: "") + ". Nothing more to do.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      else -> Unit
    }
    // End-to-end only, and only where the facility has another relay group: the server permits no other readers.
    if (canShare && letter.status != LetterStatus.MAILED) OutlinedButton(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Share with a partner group") }
    TextButton(onClick = onThread) { Text("Open the conversation") }
  }
}

/** After `create-writer.html`. */
@Composable
fun AddWriterScreen(onBack: () -> Unit, onDone: (ManagedWriter, thenWrite: Boolean) -> Unit, viewModel: AddWriterViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(ui.created) { ui.created?.let { onDone(it, ui.thenWrite) } }
  DetailScaffold(title = "Add a writer", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Creates an account in your group's care. The writer can claim it and take independent control at any time, using a handoff token you generate later.", style = MaterialTheme.typography.bodyLarge)
      Text("Anonymous letters do not need an account. Use this only to follow one person's correspondence over time.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(ui.name, viewModel::onName, label = { Text("Name") }, supportingText = { Text("Whatever they go by at your events. 3 to 32 characters. Not verified, not unique.") }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("writer-name"))
      OutlinedTextField(ui.email, viewModel::onEmail, label = { Text("Email (optional)") }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
      OutlinedTextField(ui.note, viewModel::onNote, label = { Text("Internal note (optional)") }, supportingText = { Text("Only your group sees this. Never shown to the writer.") }, minLines = 2, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
      ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Button(onClick = { viewModel.submit(thenWrite = true) }, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth()) { Text("Add writer and start a letter") }
      OutlinedButton(onClick = { viewModel.submit(thenWrite = false) }, enabled = ui.canSubmit, modifier = Modifier.fillMaxWidth().testTag("writer-add")) { Text("Add writer") }
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
  DetailScaffold(title = "Hand off account", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Writer: ${ui.writerName}", style = MaterialTheme.typography.titleLarge)
      Text("A one-time claim token lets ${ui.writerName} set their own username and password and take independent control of their correspondence.", style = MaterialTheme.typography.bodyLarge)
      listOf(
        "Once claimed, they no longer appear among your group's writers, and you can no longer write as them.",
        "Your group keeps the letters it relayed, for records and reprints. It loses their other conversations.",
        "The token works once and lasts 72 hours. Making a new one cancels the old one.",
      ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

      val token = ui.token
      if (token == null) {
        if (ui.revoked) Text("The token was revoked. It can no longer be used.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = viewModel::generate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("generate")) { Text(if (ui.busy) "Working…" else "Generate claim token") }
        // This screen cannot see an earlier token, only cancel it; once that is done there is nothing left to revoke.
        if (!ui.revoked) OutlinedButton(onClick = viewModel::revoke, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Revoke the current token") }
      } else {
        Text(
          SecretCodes.pretty(token.token).chunked(15).joinToString("\n") { it.trim('-') },
          fontFamily = FontFamily.Monospace, fontSize = 26.sp, lineHeight = 38.sp, textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 20.dp).testTag("token"),
        )
        token.expiresAt?.let { Text("Expires ${it.longDate()}. Shown once: when you leave this screen it cannot be shown again, only replaced.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SecretCodes.pretty(token.token))) }) { Text("Copy") }
        AlertBanner("Give this to ${ui.writerName} in person, or over a channel you both trust such as Signal. Do not email it or post it anywhere. They enter it in the app under Sign in, \"I have a claim token\".")
        OutlinedButton(onClick = viewModel::generate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Regenerate (cancels this one)") }
        TextButton(onClick = viewModel::revoke, enabled = !ui.busy) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
      }
      ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }
}

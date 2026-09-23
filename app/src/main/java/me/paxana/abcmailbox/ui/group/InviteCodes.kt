package me.paxana.abcmailbox.ui.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.repo.InviteRepository
import me.paxana.abcmailbox.domain.InviteBatch
import me.paxana.abcmailbox.domain.InviteCode
import me.paxana.abcmailbox.domain.InviteQuota
import me.paxana.abcmailbox.domain.IssuedInvites
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.QrCode
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.ui.directory.Loadable
import javax.inject.Inject

/** What the cancel dialog is about: one batch by id, or every batch. */
const val ALL_BATCHES = "*"

data class InviteCodesUiState(
  val quota: Loadable<InviteQuota> = Loadable.Loading,
  val countText: String = "10",
  val label: String = "",
  val daysText: String = "",
  val busy: Boolean = false,
  val error: String? = null,
  /** A batch made and not yet printed or saved, shown once; while set the screen shows the slips and nothing else. */
  val issued: IssuedInvites? = null,
  /** The batch a cancel is being confirmed for ([ALL_BATCHES] for every batch), or null. */
  val confirmCancel: String? = null,
  val cancelledNotice: Int? = null,
) {
  val count: Int? get() = countText.trim().toIntOrNull()?.takeIf { it in 1..InviteRepository.COUNT_MAX }
  val days: Int? get() = daysText.trim().toIntOrNull()?.takeIf { it >= 1 }
  val daysWrong: Boolean get() = daysText.isNotBlank() && days == null
  val countWrong: Boolean get() = countText.isNotBlank() && count == null
  val canIssue: Boolean get() = !busy && count != null && !daysWrong && label.length <= InviteRepository.LABEL_MAX
}

/**
 * A group's invite codes (API PR #116): the standing list with counts and the quota, a form to make a batch,
 * and cancelling. A batch made is shown until the person says they have printed or saved it, because the server
 * says the codes once; the repository keeps it on the phone meanwhile, so a process death loses nothing either.
 */
@HiltViewModel
class InviteCodesViewModel @Inject constructor(private val invites: InviteRepository, private val strings: Strings) : ViewModel() {
  private val _ui = MutableStateFlow(InviteCodesUiState())
  val ui: StateFlow<InviteCodesUiState> = _ui.asStateFlow()

  init {
    // A batch the app was killed on comes back first, before anything else is offered.
    viewModelScope.launch { invites.pending()?.let { kept -> _ui.update { it.copy(issued = kept) } } }
    load()
  }

  fun load() {
    viewModelScope.launch {
      val r = invites.quota()
      _ui.update { it.copy(quota = when (r) { is ApiResult.Failure -> Loadable.Failed(r.error); is ApiResult.Success -> Loadable.Loaded(r.value) }) }
    }
  }

  fun onCount(v: String) = _ui.update { it.copy(countText = v.filter(Char::isDigit).take(2), error = null, cancelledNotice = null) }
  fun onLabel(v: String) = _ui.update { it.copy(label = v.take(InviteRepository.LABEL_MAX), error = null, cancelledNotice = null) }
  fun onDays(v: String) = _ui.update { it.copy(daysText = v.filter(Char::isDigit).take(3), error = null, cancelledNotice = null) }

  fun issue() {
    val s = _ui.value
    val count = s.count ?: return
    if (!s.canIssue) return
    _ui.update { it.copy(busy = true, error = null, cancelledNotice = null) }
    viewModelScope.launch {
      when (val r = invites.issue(count, s.label, s.days)) {
        is ApiResult.Success -> _ui.update { it.copy(busy = false, issued = r.value, label = "", daysText = "") }
        // Over the quota the server answers 409 with the numbers in its sentence; that sentence is what to show.
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_generic)) }
      }
    }
  }

  /** The person has printed or saved the slips: the codes leave the screen and the phone for good, and the list is reloaded. */
  fun finishedWithCodes() {
    _ui.update { it.copy(issued = null) }
    viewModelScope.launch { invites.finished(); load() }
  }

  fun askCancel(batch: String) = _ui.update { it.copy(confirmCancel = batch, error = null, cancelledNotice = null) }
  fun dismissCancel() = _ui.update { it.copy(confirmCancel = null) }

  fun cancelConfirmed() {
    val which = _ui.value.confirmCancel ?: return
    _ui.update { it.copy(confirmCancel = null, busy = true, error = null) }
    viewModelScope.launch {
      when (val r = invites.cancel(which.takeIf { it != ALL_BATCHES })) {
        is ApiResult.Success -> { _ui.update { it.copy(busy = false, cancelledNotice = r.value) }; load() }
        is ApiResult.Failure -> _ui.update { it.copy(busy = false, error = r.error.userMessage ?: strings.get(R.string.error_generic)) }
      }
    }
  }
}

@Composable
fun InviteCodesScreen(onBack: () -> Unit, viewModel: InviteCodesViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val issued = ui.issued
  if (issued != null) {
    InviteSlipsView(issued, onDone = viewModel::finishedWithCodes)
    return
  }
  DetailScaffold(title = stringResource(R.string.title_invite_codes), onBack = onBack) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val q = ui.quota) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(q.error, onRetry = viewModel::load)
        is Loadable.Loaded -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val quota = q.value
          Text(stringResource(R.string.invite_codes_intro), style = MaterialTheme.typography.bodyLarge)
          Text(stringResource(R.string.invite_codes_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(stringResource(R.string.invite_quota, quota.outstanding, quota.limit), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("invite-quota"))
          Text(stringResource(R.string.invite_quota_explained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

          SectionTitle(stringResource(R.string.section_print_invites))
          OutlinedTextField(
            ui.countText, viewModel::onCount, label = { Text(stringResource(R.string.label_invite_count)) }, supportingText = { Text(stringResource(R.string.help_invite_count)) },
            singleLine = true, enabled = !ui.busy, isError = ui.countWrong,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("invite-count"),
          )
          OutlinedTextField(
            ui.label, viewModel::onLabel, label = { Text(stringResource(R.string.label_invite_label)) }, supportingText = { Text(stringResource(R.string.help_invite_label)) },
            singleLine = true, enabled = !ui.busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("invite-label"),
          )
          OutlinedTextField(
            ui.daysText, viewModel::onDays, label = { Text(stringResource(R.string.label_invite_days)) }, supportingText = { Text(stringResource(R.string.help_invite_days)) },
            singleLine = true, enabled = !ui.busy, isError = ui.daysWrong,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().testTag("invite-days"),
          )
          ui.error?.let { ErrorText(it) }
          ui.cancelledNotice?.let { Text(pluralStringResource(R.plurals.notice_invites_cancelled, it, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
          Button(onClick = viewModel::issue, enabled = ui.canIssue, modifier = Modifier.fillMaxWidth().testTag("invite-issue")) { Text(stringResource(if (ui.busy) R.string.action_issuing else R.string.action_issue_invites)) }

          SectionTitle(stringResource(R.string.section_invite_batches))
          if (quota.batches.isEmpty()) Text(stringResource(R.string.invite_batches_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
          quota.batches.forEach { batch -> BatchCard(batch, enabled = !ui.busy, onCancel = { viewModel.askCancel(batch.id) }) }
          if (quota.outstanding > 0) TextButton(onClick = { viewModel.askCancel(ALL_BATCHES) }, enabled = !ui.busy) { Text(stringResource(R.string.action_cancel_all_unused), color = MaterialTheme.colorScheme.error) }
        }
      }
    }
  }
  ui.confirmCancel?.let { which ->
    AlertDialog(
      onDismissRequest = viewModel::dismissCancel,
      title = { Text(stringResource(R.string.cancel_invites_title)) },
      text = { Text(stringResource(if (which == ALL_BATCHES) R.string.cancel_all_invites_text else R.string.cancel_invites_text)) },
      confirmButton = { TextButton(onClick = viewModel::cancelConfirmed, modifier = Modifier.testTag("cancel-codes-confirm")) { Text(stringResource(R.string.action_cancel_codes), color = MaterialTheme.colorScheme.error) } },
      dismissButton = { TextButton(onClick = viewModel::dismissCancel) { Text(stringResource(R.string.action_back)) } },
    )
  }
}

@Composable
private fun BatchCard(batch: InviteBatch, enabled: Boolean, onCancel: () -> Unit) {
  Card(Modifier.fillMaxWidth().testTag("batch-${batch.id}")) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(batch.label?.takeIf { it.isNotBlank() } ?: pluralStringResource(R.plurals.invite_batch_unlabelled, batch.total, batch.total), style = MaterialTheme.typography.titleMedium)
      batch.createdAt?.let { Text(stringResource(R.string.invite_batch_made, it.longDate()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      batch.expiresAt?.let { Text(stringResource(R.string.invite_batch_use_by, it.longDate()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Text(stringResource(R.string.invite_batch_counts, batch.used, batch.unused, batch.cancelled, batch.expired), style = MaterialTheme.typography.bodyMedium)
      if (batch.unused > 0) TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.action_cancel_unused)) }
    }
  }
}

/**
 * The slips of a batch just made, shown once. The codes are on the screen for a phone held up to a printer's
 * app or to a camera, and in one PDF for the print dialog and for sharing. Leaving asks first: the server will
 * not say these codes again.
 */
@Composable
private fun InviteSlipsView(issued: IssuedInvites, onDone: () -> Unit) {
  val context = LocalContext.current
  val strings = rememberStrings()
  val scope = rememberCoroutineScope()
  var leaving by remember { mutableStateOf(false) }
  var pdfError by remember { mutableStateOf<String?>(null) }
  var working by remember { mutableStateOf(false) }
  BackHandler { leaving = true }

  /** Writes the PDF off the main thread, then hands the file to [then] on it. */
  fun withPdf(then: (java.io.File) -> Unit) {
    working = true; pdfError = null
    scope.launch {
      val file = withContext(Dispatchers.IO) { runCatching { InviteSlipsPdf.write(context, issued, strings) } }
      working = false
      file.onSuccess(then).onFailure { pdfError = strings.get(R.string.error_invites_pdf) }
    }
  }
  val sharedName = stringResource(R.string.slip_shared_name, issued.label?.takeIf { it.isNotBlank() } ?: issued.groupName)

  DetailScaffold(title = stringResource(R.string.title_invite_codes), onBack = { leaving = true }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      AlertBanner(stringResource(R.string.invites_shown_once))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { withPdf { InviteSlipsPdf.print(context, it, sharedName) } }, enabled = !working, modifier = Modifier.weight(1f).testTag("invites-print")) { Text(stringResource(R.string.action_print)) }
        OutlinedButton(onClick = { withPdf { InviteSlipsPdf.share(context, it, sharedName) } }, enabled = !working, modifier = Modifier.weight(1f).testTag("invites-share")) { Text(stringResource(R.string.action_share_pdf)) }
      }
      pdfError?.let { ErrorText(it) }
      issued.codes.forEach { code -> Slip(code, issued) }
      Button(onClick = { leaving = true }, modifier = Modifier.fillMaxWidth().testTag("invites-done")) { Text(stringResource(R.string.action_finished_with_codes)) }
    }
  }
  if (leaving) {
    AlertDialog(
      onDismissRequest = { leaving = false },
      title = { Text(stringResource(R.string.invites_leave_title)) },
      text = { Text(stringResource(R.string.invites_leave_text)) },
      confirmButton = { TextButton(onClick = { leaving = false; onDone() }, modifier = Modifier.testTag("invites-leave")) { Text(stringResource(R.string.action_leave_anyway)) } },
      dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.action_stay)) } },
    )
  }
}

@Composable
private fun Slip(code: String, issued: IssuedInvites) {
  val pretty = InviteCode.pretty(code)
  Card(Modifier.fillMaxWidth().testTag("slip")) {
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.slip_inviting, issued.groupName), style = MaterialTheme.typography.bodySmall)
        // Read aloud one character at a time, as a claim token is: "7, Q, 4, M".
        Text(pretty, fontFamily = FontFamily.Monospace, fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = pretty.split('-').joinToString(". ") { g -> g.toList().joinToString(" ") } })
        issued.expiresAt?.let { Text(stringResource(R.string.invite_batch_use_by, it.longDate()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      QrCode(InviteCode.link(code), modifier = Modifier.size(96.dp), contentDescription = null)
    }
  }
}

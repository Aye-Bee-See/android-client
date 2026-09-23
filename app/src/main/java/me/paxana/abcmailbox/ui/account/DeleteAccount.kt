package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.AlertBanner
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.account.AccountEraser
import me.paxana.abcmailbox.data.account.DeletionPreview
import me.paxana.abcmailbox.data.account.DeletionReport
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.Role as AccountRole
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorText
import javax.inject.Inject

data class DeleteAccountUiState(
  /** The account's own username, to be typed back. */
  val username: String = "",
  val inGroup: Boolean = false,
  /** Null until it has been gathered. The page does not wait for it: the words are true without the numbers. */
  val preview: DeletionPreview? = null,
  val typedUsername: String = "",
  val password: String = "",
  val show: Boolean = false,
  /** Ticked by hand: the one guard that makes the person read the sentence that matters most. */
  val acknowledged: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
) {
  /**
   * Typing the username is the guard against a slip of the thumb; the password, which the server demands, is
   * the guard against a borrowed phone. Case and stray spaces are forgiven: this proves intent, not identity.
   */
  val usernameMatches: Boolean get() = username.isNotEmpty() && typedUsername.trim().equals(username, ignoreCase = true)
  val blockedByGroupKey: Boolean get() = preview?.isLastKeyHolder == true
  val blockedByOwnership: Boolean get() = preview?.isOwnerWithOthers == true
  val canSubmit: Boolean get() = !busy && usernameMatches && password.isNotEmpty() && acknowledged && !blockedByGroupKey && !blockedByOwnership
}

/**
 * Deleting one's own account (API PR #104), guarded the way the iOS app guards it, so that the two behave
 * alike. The page says in words what will go before it asks for anything. Then four things stand between a
 * person and a mistake: typing their username, their password (which the server checks), ticking "I
 * understand this cannot be undone", and a last confirmation that names the account again.
 *
 * None of the four makes anyone wait. People who use this app may need to leave in a hurry, so there is no
 * cooling-off period and no emailed link: a person who means it is through in under a minute.
 *
 * There is nothing to navigate to on success. The session goes, the shell rebuilds itself on the Account
 * page, and the receipt is shown from above all of that (see `FarewellDialog`).
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
  private val eraser: AccountEraser,
  private val sessions: SessionRepository,
  private val strings: Strings,
) : ViewModel() {
  private val user get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user
  private val _ui = MutableStateFlow(DeleteAccountUiState(username = user?.username.orEmpty(), inGroup = user?.role == AccountRole.CHAPTER))
  val ui: StateFlow<DeleteAccountUiState> = _ui.asStateFlow()

  init { load() }

  /** Also on coming back to the page: the Group key screen is one tap away from here, and may have changed the answer. */
  fun load() { viewModelScope.launch { val preview = eraser.preview(); _ui.update { it.copy(preview = preview) } } }

  fun onUsername(v: String) = _ui.update { it.copy(typedUsername = v, error = null) }
  fun onPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onToggleShow() = _ui.update { it.copy(show = !it.show) }
  fun onAcknowledge(v: Boolean) = _ui.update { it.copy(acknowledged = v) }

  /** Called from the last confirmation, never from the page's own button. */
  fun submit() {
    val s = _ui.value
    if (!s.canSubmit) return
    val role = user?.role
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = eraser.delete(s.password)) {
        is ApiResult.Success -> Unit // this screen is about to stop existing
        is ApiResult.Failure -> {
          // The password never outlives an attempt. And every message says that nothing was deleted: after
          // pressing that button, it is the first thing to know.
          _ui.update {
            it.copy(busy = false, password = "", error = when (val e = r.error) {
              is AppError.Forbidden -> strings.get(R.string.error_delete_wrong_password)
              is AppError.Network -> strings.get(R.string.error_delete_network)
              is AppError.RateLimited -> e.retryAfterSeconds?.let { sec -> strings.plural(R.plurals.error_rate_limited_minutes, ((sec + 59) / 60).toInt()) } ?: e.info ?: strings.get(R.string.error_delete_generic)
              // The server explains a refusal in English, with an endpoint name in it, and does not say which of its
              // reasons applies except in that sentence. The account's role decides the words (asked of the API: a reason code).
              is AppError.Conflict -> strings.get(when {
                e.name != "AccountDeleteError" -> R.string.error_delete_generic
                role == AccountRole.ADMIN -> R.string.error_delete_only_admin
                // Two refusals for a group admin, told apart by the server's sentence until it sends a reason code (PLAN.md, ask 16).
                s.inGroup && e.info?.contains("owner", ignoreCase = true) == true -> R.string.error_delete_owner
                s.inGroup -> R.string.error_delete_last_key_holder
                else -> R.string.error_delete_refused
              })
              else -> strings.get(R.string.error_delete_generic)
            })
          }
          load() // a refusal may be about the group key, which the notice explains better than a red line
        }
      }
    }
  }
}

@Composable
fun DeleteAccountScreen(onBack: () -> Unit, onGroupKey: () -> Unit = {}, viewModel: DeleteAccountViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  // Not saved across rotation on purpose: a confirmation that reappears by itself is one nobody asked for.
  var confirming by remember { mutableStateOf(false) }
  LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }

  DetailScaffold(title = stringResource(R.string.delete_title), onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AlertBanner(stringResource(R.string.delete_banner))

      SectionTitle(stringResource(R.string.delete_goes_heading))
      val p = ui.preview
      // With their own number when there is one: "your 12 conversations" is harder to tap past than "your data".
      val threads = p?.threads ?: 0
      Bullet(if (threads > 0) pluralStringResource(R.plurals.delete_goes_threads, threads, threads) else stringResource(R.string.delete_goes_letters))
      Bullet(stringResource(R.string.delete_goes_files))
      val unsent = p?.unsentOnPhone ?: 0
      Bullet(if (unsent > 0) pluralStringResource(R.plurals.delete_goes_unsent, unsent, unsent) else stringResource(R.string.delete_goes_phone))
      Bullet(stringResource(R.string.delete_goes_name))
      if (p?.endToEnd == true) Bullet(stringResource(R.string.delete_goes_keys))

      SectionTitle(stringResource(R.string.delete_cannot_heading))
      Bullet(stringResource(R.string.delete_cannot_paper))
      if (ui.inGroup) Bullet(stringResource(R.string.delete_cannot_group))

      if (p != null && p.isLastKeyHolder) LastKeyHolderNotice(p, onGroupKey)
      else if (p != null && p.isOwnerWithOthers) OwnerNotice(onGroupKey)
      else Form(ui, viewModel, onAsk = { confirming = true }, onBack = onBack)
    }
  }

  if (confirming) AlertDialog(
    onDismissRequest = { confirming = false },
    title = { Text(stringResource(R.string.delete_confirm_title, ui.username)) },
    text = { Text(stringResource(R.string.delete_confirm_body)) },
    confirmButton = {
      TextButton(onClick = { confirming = false; viewModel.submit() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("delete-confirm")) {
        Text(stringResource(R.string.action_delete_everything))
      }
    },
    dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_keep_account)) } },
  )
}

/** The group-owner admin of a chapter with other group admins: pass the role on first (API PR #115). */
@Composable
private fun OwnerNotice(onGroupKey: () -> Unit) {
  SectionTitle(stringResource(R.string.delete_not_yet_heading))
  AlertBanner(stringResource(R.string.delete_owner_notice))
  Button(onClick = onGroupKey, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_group_key)) }
}

/** In place of the form: there is no point asking for a password the server is certain to turn down. */
@Composable
private fun LastKeyHolderNotice(p: DeletionPreview, onGroupKey: () -> Unit) {
  SectionTitle(stringResource(R.string.delete_not_yet_heading))
  AlertBanner(stringResource(R.string.delete_last_holder_notice))
  Text(
    if (p.membersWhoCouldHoldTheKey.isEmpty()) stringResource(R.string.delete_nobody_to_hand) else stringResource(R.string.delete_could_hand, p.membersWhoCouldHoldTheKey.joinToString(", ")),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Button(onClick = onGroupKey, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_group_key)) }
}

@Composable
private fun Form(ui: DeleteAccountUiState, viewModel: DeleteAccountViewModel, onAsk: () -> Unit, onBack: () -> Unit) {
  SectionTitle(stringResource(R.string.delete_sure_heading))
  // The instruction sits above the field, not in it: a floating label this long is cut off once the field has focus.
  Text(stringResource(R.string.label_type_username, ui.username), style = MaterialTheme.typography.bodyLarge)
  OutlinedTextField(
    ui.typedUsername, viewModel::onUsername, label = { Text(stringResource(R.string.label_username)) },
    singleLine = true, enabled = !ui.busy, isError = ui.typedUsername.isNotEmpty() && !ui.usernameMatches,
    // A username is not a word: no capital first letter, no autocorrect "fixing" it.
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    modifier = Modifier.fillMaxWidth().testTag("delete-username"),
  )
  OutlinedTextField(
    ui.password, viewModel::onPassword, label = { Text(stringResource(R.string.label_your_password)) },
    supportingText = { Text(stringResource(R.string.help_password_to_delete)) },
    singleLine = true, enabled = !ui.busy,
    visualTransformation = if (ui.show) VisualTransformation.None else PasswordVisualTransformation(),
    // Done on the keyboard closes the keyboard. It does not press the red button: that takes a finger on the button.
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { defaultKeyboardAction(ImeAction.Done) }),
    trailingIcon = { TextButton(onClick = viewModel::onToggleShow) { Text(stringResource(if (ui.show) R.string.action_hide else R.string.action_show)) } },
    modifier = Modifier.fillMaxWidth().testTag("delete-password"),
  )
  // The whole row is the control (48dp, one thing for a screen reader to land on), not the little box alone.
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.acknowledged, enabled = !ui.busy, role = Role.Checkbox, onValueChange = viewModel::onAcknowledge).testTag("delete-ack"),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(checked = ui.acknowledged, onCheckedChange = null, enabled = !ui.busy)
    Text(stringResource(R.string.delete_ack), style = MaterialTheme.typography.bodyLarge)
  }
  ui.error?.let { ErrorText(it) }
  // The one red button in the app. It deletes nothing: it asks one last time.
  Button(
    onClick = onAsk, enabled = ui.canSubmit,
    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    modifier = Modifier.fillMaxWidth().testTag("delete-submit"),
  ) { Text(stringResource(if (ui.busy) R.string.action_deleting else R.string.action_delete_forever)) }
  // The way out is as big as the way through.
  OutlinedButton(onClick = onBack, enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.action_keep_account))
  }
}

@Composable
private fun Bullet(text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * The receipt. A dialog, not a snackbar: it is the only proof the person will ever get, and it should stay
 * until they have read it. Shown from above the navigation, which is rebuilt the moment the session goes.
 */
@Composable
fun FarewellDialog(report: DeletionReport, onClose: () -> Unit) {
  val lines = listOfNotNull(
    report.letters.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.farewell_letters, it, it) },
    report.replies.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.farewell_replies, it, it) },
    report.attachments.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.farewell_files, it, it) },
    report.threads.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.farewell_threads, it, it) },
    report.unsentOnPhone.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.farewell_unsent, it, it) },
  )
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text(stringResource(R.string.farewell_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.farewell_heading))
        if (lines.isEmpty()) Bullet(stringResource(R.string.farewell_nothing)) else lines.forEach { Bullet(it) }
        Text(stringResource(R.string.farewell_footer), modifier = Modifier.padding(top = 8.dp))
      }
    },
    confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
  )
}

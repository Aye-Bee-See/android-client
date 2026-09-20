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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.session.Role as AccountRole
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.asHeading
import javax.inject.Inject

data class DeleteAccountUiState(
  /** Null until the numbers are in. The page does not wait for them: the words are true without them. */
  val preview: DeletionPreview? = null,
  val endToEnd: Boolean = false,
  val inGroup: Boolean = false,
  val acknowledged: Boolean = false,
  val password: String = "",
  val show: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
) {
  /** Two deliberate acts, both of them: saying "I understand", and proving who is holding the phone. */
  val canSubmit: Boolean get() = !busy && acknowledged && password.isNotEmpty()
}

/**
 * The guard in front of "delete my account". What it is for: stopping an accident, and stopping somebody
 * else. What it is not for: slowing down the owner. People who use this app may need to leave in a hurry,
 * so there is no waiting period, no countdown, no "type DELETE": a page that says what goes, one tick,
 * one password (which the server insists on anyway), one red button.
 *
 * There is nothing to navigate to on success. The session goes, the shell rebuilds itself on the Account
 * page, and the receipt is shown from above all of that (see `FarewellDialog`).
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
  private val eraser: AccountEraser,
  private val sessions: SessionRepository,
  modes: EncryptionModeRepository,
  private val strings: Strings,
) : ViewModel() {
  private val user get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user
  private val _ui = MutableStateFlow(DeleteAccountUiState(inGroup = user?.let { it.isStaff && it.chapterId != null } == true))
  val ui: StateFlow<DeleteAccountUiState> = _ui.asStateFlow()

  init {
    viewModelScope.launch { _ui.update { it.copy(endToEnd = modes.current() == EncryptionMode.E2E) } }
    viewModelScope.launch { val preview = eraser.preview(); _ui.update { it.copy(preview = preview) } }
  }

  fun onAcknowledge(v: Boolean) = _ui.update { it.copy(acknowledged = v) }
  fun onPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
  fun onToggleShow() = _ui.update { it.copy(show = !it.show) }

  fun submit() {
    val s = _ui.value
    if (!s.canSubmit) return
    val role = user?.role
    _ui.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      when (val r = eraser.delete(s.password)) {
        is ApiResult.Success -> Unit // this screen is about to stop existing
        is ApiResult.Failure -> _ui.update {
          // Every one of these says that nothing was deleted: after pressing that button, it is the first thing to know.
          it.copy(busy = false, password = if (r.error is AppError.Forbidden) "" else it.password, error = when (val e = r.error) {
            is AppError.Forbidden -> strings.get(R.string.error_delete_wrong_password)
            is AppError.Network -> strings.get(R.string.error_delete_network)
            is AppError.RateLimited -> e.retryAfterSeconds?.let { sec -> strings.plural(R.plurals.error_rate_limited_minutes, ((sec + 59) / 60).toInt()) } ?: e.info ?: strings.get(R.string.error_delete_generic)
            // The server explains a refusal in English, with an endpoint name in it. It does not say which of its
            // reasons applies except in that sentence, so the account's role decides the words (asked of the API: a reason code).
            is AppError.Conflict -> strings.get(when {
              e.name != "AccountDeleteError" -> R.string.error_delete_generic
              role == AccountRole.ADMIN -> R.string.error_delete_only_admin
              s.inGroup -> R.string.error_delete_last_key_holder
              else -> R.string.error_delete_refused
            })
            else -> strings.get(R.string.error_delete_generic)
          })
        }
      }
    }
  }
}

@Composable
fun DeleteAccountScreen(onBack: () -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  DetailScaffold(title = stringResource(R.string.delete_title), onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.delete_intro), style = MaterialTheme.typography.bodyLarge)

      Text(stringResource(R.string.delete_goes_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
      Bullet(stringResource(R.string.delete_goes_letters))
      Bullet(stringResource(R.string.delete_goes_files))
      // With their own number when the server could be asked: "all 12 of your conversations" is harder to tap past than "your data".
      val threads = ui.preview?.threads
      if (threads == null) Bullet(stringResource(R.string.delete_goes_threads_unknown))
      else if (threads > 0) Bullet(pluralStringResource(R.plurals.delete_goes_threads, threads, threads))
      ui.preview?.unsentOnPhone?.takeIf { it > 0 }?.let { Bullet(pluralStringResource(R.plurals.delete_goes_unsent, it, it)) }
      Bullet(stringResource(R.string.delete_goes_phone))
      if (ui.endToEnd) Bullet(stringResource(R.string.delete_goes_keys))
      Bullet(stringResource(R.string.delete_goes_name))

      Text(stringResource(R.string.delete_cannot_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
      Bullet(stringResource(R.string.delete_cannot_paper))
      if (ui.inGroup) Bullet(stringResource(R.string.delete_cannot_group))

      HorizontalDivider()
      // The whole row is the control (48dp, one thing for a screen reader to land on), not the little box alone.
      Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = ui.acknowledged, enabled = !ui.busy, role = Role.Checkbox, onValueChange = viewModel::onAcknowledge).testTag("delete-ack"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Checkbox(checked = ui.acknowledged, onCheckedChange = null, enabled = !ui.busy)
        Text(stringResource(R.string.delete_ack), style = MaterialTheme.typography.bodyLarge)
      }
      OutlinedTextField(
        ui.password, viewModel::onPassword, label = { Text(stringResource(R.string.label_your_password)) },
        supportingText = { Text(stringResource(R.string.help_password_to_delete)) },
        singleLine = true, enabled = !ui.busy, isError = ui.error != null && ui.password.isEmpty(),
        visualTransformation = if (ui.show) VisualTransformation.None else PasswordVisualTransformation(),
        // Done on the keyboard closes the keyboard. It does not press the red button: that takes a finger on the button.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { defaultKeyboardAction(ImeAction.Done) }),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShow) { Text(stringResource(if (ui.show) R.string.action_hide else R.string.action_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("delete-password"),
      )
      ui.error?.let { ErrorText(it) }
      Button(
        onClick = viewModel::submit, enabled = ui.canSubmit,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.fillMaxWidth().testTag("delete-submit"),
      ) { Text(stringResource(if (ui.busy) R.string.action_deleting else R.string.action_delete_forever)) }
      // The way out is as big as the way through.
      OutlinedButton(onClick = onBack, enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_keep_account))
      }
    }
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

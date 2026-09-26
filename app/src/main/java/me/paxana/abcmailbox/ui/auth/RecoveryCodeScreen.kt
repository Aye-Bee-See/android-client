package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.SecretCodeText
import me.paxana.abcmailbox.ui.common.asHeading
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.paxana.abcmailbox.crypto.SecretCodes
import me.paxana.abcmailbox.ui.common.AlertBanner

/**
 * Shown exactly once, right after keys are created or an account is claimed.
 * The code is the only way back in if the password is lost, and nobody else
 * has it, so the screen cannot be left without ticking the box: the system
 * back gesture is swallowed on purpose.
 */
@Composable
fun RecoveryCodeScreen(code: String, onSaved: () -> Unit, lettersCaughtUp: Int = 0) {
  var saved by rememberSaveable { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  BackHandler(enabled = true) { }

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    Text(
      stringResource(R.string.recovery_explained),
      style = MaterialTheme.typography.bodyLarge,
    )
    // Letters written to them before they had keys, which the server has just sealed to the new key.
    if (lettersCaughtUp > 0) Text(pluralStringResource(R.plurals.recovery_letters_caught_up, lettersCaughtUp, lettersCaughtUp), style = MaterialTheme.typography.bodyLarge)
    SecretCodeText(code, modifier = Modifier.testTag("recovery-code"))
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SecretCodes.pretty(code))) }) { Text(stringResource(R.string.action_copy)) }
    AlertBanner(stringResource(R.string.recovery_keep_safe))
    // The whole row is the control: tapping the sentence ticks the box, and a screen reader hears one checkbox with its label.
    Row(
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth().toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }).padding(vertical = 12.dp).testTag("recovery-saved"),
    ) {
      Checkbox(checked = saved, onCheckedChange = null)
      Text(stringResource(R.string.recovery_saved), style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onSaved, enabled = saved, modifier = Modifier.fillMaxWidth().testTag("recovery-continue")) { Text(stringResource(R.string.action_continue)) }
  }
}

package me.paxana.abcmailbox.ui.auth

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
fun RecoveryCodeScreen(code: String, onSaved: () -> Unit) {
  var saved by rememberSaveable { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  BackHandler(enabled = true) { }

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Save your recovery code", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Your letters are encrypted with a key only you hold. Your password unlocks it. If you ever forget the password, this code is the only other way in. We cannot see it and cannot send it to you again.",
      style = MaterialTheme.typography.bodyLarge,
    )
    Text(
      SecretCodes.pretty(code).chunked(15).joinToString("\n") { it.trim('-') },
      fontFamily = FontFamily.Monospace, fontSize = 26.sp, lineHeight = 38.sp, textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 20.dp).testTag("recovery-code"),
    )
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SecretCodes.pretty(code))) }) { Text("Copy") }
    AlertBanner("Write it on paper or put it in a password manager. Do not keep it only on this phone, and do not send it to anyone.")
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = saved, onCheckedChange = { saved = it }, modifier = Modifier.testTag("recovery-saved"))
      Text("I have saved this code somewhere safe.", style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onSaved, enabled = saved, modifier = Modifier.fillMaxWidth().testTag("recovery-continue")) { Text("Continue") }
  }
}

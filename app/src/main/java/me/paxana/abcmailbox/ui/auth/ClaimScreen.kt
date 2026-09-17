package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.UppercaseTransformation
import me.paxana.abcmailbox.ui.common.longDate

/** After `claim.html`: take over an account a support group created for you. */
@Composable
fun ClaimScreen(
  sessionState: SessionState,
  mode: EncryptionMode,
  onClaimed: () -> Unit,
  onBack: () -> Unit,
  viewModel: ClaimViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(sessionState) { if (sessionState is SessionState.SignedIn && ui.info != null) onClaimed() }

  DetailScaffold(title = "Claim your account", onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val info = ui.info
      if (info == null) {
        Text("A support group created an account for you and gave you a one-time token. Enter it to take control of your correspondence.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
          value = ui.token,
          onValueChange = viewModel::onTokenChange,
          label = { Text("Claim token") },
          placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          singleLine = true,
          enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.check() }),
          modifier = Modifier.fillMaxWidth().testTag("token"),
        )
        Text("24 letters and digits. Dashes, spaces, and lower case are fine. The letters I, L, O, and U are never used.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = viewModel::check, enabled = ui.canCheck, modifier = Modifier.fillMaxWidth()) { Text(if (ui.busy) "Checking…" else "Check token") }
        return@Column
      }

      Text("Set up your account", style = MaterialTheme.typography.headlineSmall)
      Text(
        "This account (${info.writerName}) was created for you" + (info.groupName?.let { " by $it" } ?: "") + ". Choose a username and password to take independent control of your correspondence." +
          (info.expiresAt?.let { " The token expires on ${it.longDate()}." } ?: ""),
        style = MaterialTheme.typography.bodyLarge,
      )
      AlertBanner(
        if (mode == EncryptionMode.E2E || info.endToEnd) {
          "Your password protects your encryption key. No one, not this site and not your group, can read your letters without it. After this step you will get a recovery code: it is the only way back in if you forget the password."
        } else {
          "There is no \"email me a reset link\". Keep your password somewhere safe; if you lose it, a network admin has to help you."
        }
      )

      OutlinedTextField(ui.username, viewModel::onUsernameChange, label = { Text("Username") }, supportingText = { Text("3 to 16 characters") }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("claim-username"))
      OutlinedTextField(ui.password, viewModel::onPasswordChange, label = { Text("Password") }, supportingText = { Text("At least 7 characters") }, singleLine = true, enabled = !ui.busy,
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShowPassword) { Text(if (ui.showPassword) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth().testTag("claim-password"))
      OutlinedTextField(ui.confirm, viewModel::onConfirmChange, label = { Text("Confirm password") }, singleLine = true, enabled = !ui.busy,
        isError = ui.confirm.isNotEmpty() && !ui.passwordsMatch,
        supportingText = { if (ui.confirm.isNotEmpty() && !ui.passwordsMatch) Text("Passwords do not match.") },
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("claim-confirm"))
      OutlinedTextField(ui.email, viewModel::onEmailChange, label = { Text("Email (optional)") }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = ui.understood, onCheckedChange = viewModel::onUnderstoodChange, enabled = !ui.busy, modifier = Modifier.testTag("claim-understood"))
        Text("I understand that a lost password cannot be reset by email.", style = MaterialTheme.typography.bodyMedium)
      }

      if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
      Button(onClick = viewModel::claim, enabled = ui.canClaim, modifier = Modifier.fillMaxWidth().testTag("claim-submit")) { Text(if (ui.busy) "Claiming… this takes a few seconds" else "Claim account") }
      TextButton(onClick = viewModel::startOver, enabled = !ui.busy) { Text("Use a different token") }
    }
  }
}

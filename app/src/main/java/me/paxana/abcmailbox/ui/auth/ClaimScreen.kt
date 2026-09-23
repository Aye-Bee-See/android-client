package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.ui.common.PasswordStrengthMeter
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.asHeading
import me.paxana.abcmailbox.ui.common.ErrorText
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
  LaunchedEffect(sessionState, ui.claimed) { if (sessionState is SessionState.SignedIn && ui.claimed) onClaimed() }

  DetailScaffold(title = stringResource(R.string.title_claim), onBack = onBack) { padding ->
    // Opened by a claim link while an account is signed in: nothing is taken over until that is dealt with.
    if (sessionState is SessionState.SignedIn && !ui.claimed) {
      SignedInNotice(padding, stringResource(R.string.claim_signed_in, sessionState.session.user.username), onSignOut = viewModel::signOut, onBack = onBack)
      return@DetailScaffold
    }
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val info = ui.info
      if (info == null) {
        Text(stringResource(R.string.claim_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
          value = ui.token,
          onValueChange = viewModel::onTokenChange,
          label = { Text(stringResource(R.string.label_claim_token)) },
          placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          singleLine = true,
          enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.check() }),
          modifier = Modifier.fillMaxWidth().testTag("token"),
        )
        Text(stringResource(R.string.claim_token_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
        Button(onClick = viewModel::check, enabled = ui.canCheck, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (ui.busy) R.string.action_checking else R.string.action_check_token)) }
        return@Column
      }

      Text(stringResource(R.string.claim_setup_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
      Text(info.groupName?.let { stringResource(R.string.claim_created_for_you_by, info.writerName, it) } ?: stringResource(R.string.claim_created_for_you, info.writerName), style = MaterialTheme.typography.bodyLarge)
      // The same date the volunteer was shown, on its own line so that it is seen: there is no hurry, and there is a limit.
      info.expiresAt?.let { Text(stringResource(R.string.claim_token_expires, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("claim-good-until")) }
      AlertBanner(
        if (mode == EncryptionMode.E2E || info.endToEnd) {
          stringResource(R.string.claim_warning_e2e)
        } else {
          stringResource(R.string.claim_warning_server)
        }
      )

      OutlinedTextField(ui.username, viewModel::onUsernameChange, label = { Text(stringResource(R.string.label_username)) }, supportingText = { Text(stringResource(R.string.help_username_length)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("claim-username"))
      OutlinedTextField(ui.password, viewModel::onPasswordChange, label = { Text(stringResource(R.string.label_password)) }, supportingText = { Text(stringResource(R.string.help_password_length)) }, singleLine = true, enabled = !ui.busy,
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShowPassword) { Text(stringResource(if (ui.showPassword) R.string.action_hide else R.string.action_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("claim-password"))
      PasswordStrengthMeter(ui.password)
      OutlinedTextField(ui.confirm, viewModel::onConfirmChange, label = { Text(stringResource(R.string.label_confirm_password)) }, singleLine = true, enabled = !ui.busy,
        isError = ui.confirm.isNotEmpty() && !ui.passwordsMatch,
        supportingText = { if (ui.confirm.isNotEmpty() && !ui.passwordsMatch) Text(stringResource(R.string.error_passwords_differ)) },
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("claim-confirm"))
      PenNameField(ui.penName, viewModel::onPenNameChange, enabled = !ui.busy, strings = me.paxana.abcmailbox.text.rememberStrings())
      Text(stringResource(R.string.help_pen_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(ui.email, viewModel::onEmailChange, label = { Text(stringResource(R.string.label_email_optional)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())

      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = ui.understood, onCheckedChange = viewModel::onUnderstoodChange, enabled = !ui.busy, modifier = Modifier.testTag("claim-understood"))
        Text(stringResource(R.string.claim_understood), style = MaterialTheme.typography.bodyMedium)
      }

      if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
      Button(onClick = viewModel::claim, enabled = ui.canClaim, modifier = Modifier.fillMaxWidth().testTag("claim-submit")) { Text(stringResource(if (ui.busy) R.string.action_claiming else R.string.action_claim_account)) }
      TextButton(onClick = viewModel::startOver, enabled = !ui.busy) { Text(stringResource(R.string.action_different_token)) }
    }
  }
}

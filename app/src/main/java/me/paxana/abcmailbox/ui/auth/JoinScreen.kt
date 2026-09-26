package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.PasswordStrengthMeter
import me.paxana.abcmailbox.ui.common.UppercaseTransformation
import me.paxana.abcmailbox.ui.common.asHeading
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.text.rememberStrings

/** A newcomer with a slip from a support group makes their own account (API PR #116). */
@Composable
fun JoinScreen(
  sessionState: SessionState,
  mode: EncryptionMode,
  onJoined: () -> Unit,
  onBack: () -> Unit,
  onInvitationToken: (String) -> Unit = {},
  viewModel: JoinViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(sessionState, ui.joined) { if (sessionState is SessionState.SignedIn && ui.joined) onJoined() }
  LaunchedEffect(ui.invitationToken) { ui.invitationToken?.let { viewModel.invitationTokenHandedOn(); onInvitationToken(it) } }

  DetailScaffold(title = stringResource(R.string.title_join), onBack = onBack) { padding ->
    // Opened (by a slip's link, say) while an account is signed in: nothing is made until that is dealt with.
    if (sessionState is SessionState.SignedIn && !ui.joined) {
      SignedInNotice(padding, stringResource(R.string.join_signed_in, sessionState.session.user.username), onSignOut = viewModel::signOut, onBack = onBack)
      return@DetailScaffold
    }
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val invitation = ui.invitation
      if (invitation == null) {
        Text(stringResource(R.string.join_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
          value = ui.code,
          onValueChange = viewModel::onCodeChange,
          label = { Text(stringResource(R.string.label_invite_code)) },
          placeholder = { Text("XXXX-XXXX-XXXX") },
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          singleLine = true,
          enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.check() }),
          modifier = Modifier.fillMaxWidth().testTag("invite-code"),
        )
        Text(stringResource(R.string.invite_code_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.codeDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
        Button(onClick = viewModel::check, enabled = ui.canCheck, modifier = Modifier.fillMaxWidth().testTag("invite-check")) { Text(stringResource(if (ui.busy) R.string.action_checking else R.string.action_check_code)) }
        return@Column
      }

      Text(stringResource(R.string.join_setup_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
      Text(stringResource(R.string.join_invited_by, invitation.groupName), style = MaterialTheme.typography.bodyLarge)
      // The date the server gave, on its own line: there is no hurry, and there is a limit.
      invitation.expiresAt?.let { Text(stringResource(R.string.join_good_until, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("join-good-until")) }
      AlertBanner(stringResource(if (mode == EncryptionMode.E2E) R.string.claim_warning_e2e else R.string.claim_warning_server))
      Text(stringResource(R.string.join_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

      OutlinedTextField(ui.username, viewModel::onUsernameChange, label = { Text(stringResource(R.string.label_username)) }, supportingText = { Text(stringResource(R.string.help_username_length)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("join-username"))
      OutlinedTextField(ui.password, viewModel::onPasswordChange, label = { Text(stringResource(R.string.label_password)) }, supportingText = { Text(stringResource(R.string.help_password_length)) }, singleLine = true, enabled = !ui.busy,
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShowPassword) { Text(stringResource(if (ui.showPassword) R.string.action_hide else R.string.action_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("join-password"))
      PasswordStrengthMeter(ui.password)
      OutlinedTextField(ui.confirm, viewModel::onConfirmChange, label = { Text(stringResource(R.string.label_confirm_password)) }, singleLine = true, enabled = !ui.busy,
        isError = ui.confirm.isNotEmpty() && !ui.passwordsMatch,
        supportingText = { if (ui.confirm.isNotEmpty() && !ui.passwordsMatch) Text(stringResource(R.string.error_passwords_differ)) },
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("join-confirm"))
      PenNameField(ui.penName, viewModel::onPenNameChange, enabled = !ui.busy, strings = rememberStrings())
      Text(stringResource(R.string.help_pen_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(ui.name, viewModel::onNameChange, label = { Text(stringResource(R.string.label_name_optional)) }, supportingText = { Text(stringResource(R.string.help_name_optional)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("join-name"))
      OutlinedTextField(ui.email, viewModel::onEmailChange, label = { Text(stringResource(R.string.label_email_optional)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())

      if (ui.codeDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
      Button(onClick = viewModel::join, enabled = ui.canJoin, modifier = Modifier.fillMaxWidth().testTag("join-submit")) { Text(stringResource(if (ui.busy) R.string.action_joining else R.string.action_join)) }
      TextButton(onClick = viewModel::startOver, enabled = !ui.busy) { Text(stringResource(R.string.action_different_code)) }
    }
  }
}

package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import me.paxana.abcmailbox.domain.GroupInvitation
import me.paxana.abcmailbox.domain.InvitationAccepted
import me.paxana.abcmailbox.domain.NetworkRoles
import me.paxana.abcmailbox.domain.NewGroupProfile
import me.paxana.abcmailbox.domain.Services
import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorText
import me.paxana.abcmailbox.ui.common.PasswordStrengthMeter
import me.paxana.abcmailbox.ui.common.UppercaseTransformation
import me.paxana.abcmailbox.ui.common.asHeading
import me.paxana.abcmailbox.ui.common.longDate

/**
 * Someone handed an invitation token by a group: to help run that group, or to bring a new group into the network.
 * An invite code typed here goes to the join screen ([onInviteCode]), and a token no invitation has may be offered as
 * a claim token ([onClaimToken]).
 */
@Composable
fun InvitationScreen(
  sessionState: SessionState,
  mode: EncryptionMode,
  onAccepted: (InvitationAccepted) -> Unit,
  onInviteCode: (String) -> Unit,
  onClaimToken: (String) -> Unit,
  onBack: () -> Unit,
  viewModel: InvitationViewModel = hiltViewModel(),
) {
  val ui by viewModel.ui.collectAsStateWithLifecycle()
  LaunchedEffect(sessionState, ui.accepted) { val a = ui.accepted; if (sessionState is SessionState.SignedIn && a != null) onAccepted(a) }
  LaunchedEffect(ui.inviteCode) { ui.inviteCode?.let { viewModel.inviteCodeHandedOn(); onInviteCode(it) } }

  DetailScaffold(title = stringResource(R.string.title_invitation), onBack = onBack) { padding ->
    if (sessionState is SessionState.SignedIn && ui.accepted == null) {
      SignedInNotice(padding, stringResource(R.string.invitation_signed_in, sessionState.session.user.username), onSignOut = viewModel::signOut, onBack = onBack)
      return@DetailScaffold
    }
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val invitation = ui.invitation
      if (invitation == null) {
        Text(stringResource(R.string.invitation_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
          value = ui.token,
          onValueChange = viewModel::onTokenChange,
          label = { Text(stringResource(R.string.label_invitation_token)) },
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          visualTransformation = UppercaseTransformation,
          singleLine = true,
          enabled = !ui.busy,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.check() }),
          modifier = Modifier.fillMaxWidth().testTag("invitation-token"),
        )
        Text(stringResource(R.string.invitation_token_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
        Button(onClick = viewModel::check, enabled = ui.canCheck, modifier = Modifier.fillMaxWidth().testTag("invitation-check")) { Text(stringResource(if (ui.busy) R.string.action_checking else R.string.action_check_invitation)) }
        if (ui.maybeClaimToken) TextButton(onClick = { onClaimToken(ui.token) }, modifier = Modifier.testTag("invitation-try-claim")) { Text(stringResource(R.string.action_try_as_claim_token)) }
        return@Column
      }

      // What the invitation is, in the inviter's words, before anything is asked for.
      when (invitation.kind) {
        GroupInvitation.Kind.MEMBER -> {
          Text(stringResource(R.string.invitation_member_title, invitation.groupName.orEmpty()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
          Text(stringResource(R.string.invitation_member_text), style = MaterialTheme.typography.bodyLarge)
        }
        GroupInvitation.Kind.GROUP -> {
          Text(stringResource(R.string.invitation_group_title, invitation.inviteeName), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
          Text(invitation.groupName?.let { stringResource(R.string.invitation_group_vouched, it) } ?: stringResource(R.string.invitation_group_by_admin), style = MaterialTheme.typography.bodyLarge)
          if (!invitation.activatesAtOnce) Text(stringResource(R.string.invitation_group_review), style = MaterialTheme.typography.bodyMedium)
        }
      }
      invitation.expiresAt?.let { Text(stringResource(R.string.invitation_good_until, it.longDate()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      AlertBanner(stringResource(if (mode == EncryptionMode.E2E) R.string.claim_warning_e2e else R.string.claim_warning_server))

      Text(stringResource(R.string.join_setup_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
      OutlinedTextField(ui.username, viewModel::onUsernameChange, label = { Text(stringResource(R.string.label_username)) }, supportingText = { Text(stringResource(R.string.help_username_length)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("invitation-username"))
      OutlinedTextField(ui.password, viewModel::onPasswordChange, label = { Text(stringResource(R.string.label_password)) }, supportingText = { Text(stringResource(R.string.help_password_length)) }, singleLine = true, enabled = !ui.busy,
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = { TextButton(onClick = viewModel::onToggleShowPassword) { Text(stringResource(if (ui.showPassword) R.string.action_hide else R.string.action_show)) } },
        modifier = Modifier.fillMaxWidth().testTag("invitation-password"))
      PasswordStrengthMeter(ui.password)
      OutlinedTextField(ui.confirm, viewModel::onConfirmChange, label = { Text(stringResource(R.string.label_confirm_password)) }, singleLine = true, enabled = !ui.busy,
        isError = ui.confirm.isNotEmpty() && !ui.passwordsMatch,
        supportingText = { if (ui.confirm.isNotEmpty() && !ui.passwordsMatch) Text(stringResource(R.string.error_passwords_differ)) },
        visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("invitation-confirm"))
      OutlinedTextField(ui.email, viewModel::onEmailChange, label = { Text(stringResource(R.string.label_email_required)) }, supportingText = { Text(stringResource(R.string.help_invitation_email)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("invitation-email"))
      OutlinedTextField(ui.name, viewModel::onNameChange, label = { Text(stringResource(R.string.label_name_optional)) }, singleLine = true, enabled = !ui.busy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())

      if (invitation.kind == GroupInvitation.Kind.GROUP) GroupProfileFields(ui.group, invitation.groupFields, enabled = !ui.busy, onChange = viewModel::onGroupChange)

      if (ui.tokenDead) AlertBanner(ui.error.orEmpty()) else ui.error?.let { ErrorText(it) }
      Button(onClick = viewModel::accept, enabled = ui.canAccept, modifier = Modifier.fillMaxWidth().testTag("invitation-accept")) { Text(stringResource(if (ui.busy) R.string.action_accepting else R.string.action_accept_invitation)) }
      TextButton(onClick = viewModel::startOver, enabled = !ui.busy) { Text(stringResource(R.string.action_different_token)) }
    }
  }
}

/** The new group's profile: a name and a city always, and the rest only where the invitation lists the field. */
@Composable
private fun GroupProfileFields(group: NewGroupProfile, allowed: Set<String>, enabled: Boolean, onChange: ((NewGroupProfile) -> NewGroupProfile) -> Unit) {
  Text(stringResource(R.string.invitation_group_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
  Text(stringResource(R.string.invitation_group_section_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  @Composable fun field(value: String, label: Int, change: (NewGroupProfile, String) -> NewGroupProfile, tag: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1) =
    OutlinedTextField(value, { v -> onChange { change(it, v) } }, label = { Text(stringResource(label)) }, singleLine = lines == 1, minLines = lines, enabled = enabled,
      keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None, imeAction = if (lines == 1) ImeAction.Next else ImeAction.Default),
      modifier = Modifier.fillMaxWidth().testTag(tag))
  field(group.name, R.string.label_group_name, { g, v -> g.copy(name = v) }, "group-name")
  field(group.city, R.string.label_group_city, { g, v -> g.copy(city = v) }, "group-city")
  if ("subregion" in allowed) field(group.region, R.string.label_group_region, { g, v -> g.copy(region = v) }, "group-region")
  if ("country" in allowed) field(group.country, R.string.label_group_country, { g, v -> g.copy(country = v) }, "group-country")
  if ("about" in allowed) field(group.about, R.string.label_group_about, { g, v -> g.copy(about = v) }, "group-about", lines = 3)
  if ("website" in allowed) field(group.website, R.string.label_group_website, { g, v -> g.copy(website = v) }, "group-website", KeyboardType.Uri)
  if ("email" in allowed) field(group.email, R.string.label_group_email, { g, v -> g.copy(email = v) }, "group-email", KeyboardType.Email)

  if ("networkRole" in allowed) {
    Text(stringResource(R.string.label_group_role), style = MaterialTheme.typography.titleSmall)
    NetworkRoles.keys.forEach { key ->
      Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().selectable(selected = group.networkRole == key, enabled = enabled, role = Role.RadioButton, onClick = { onChange { it.copy(networkRole = key) } }).padding(vertical = 6.dp),
      ) {
        RadioButton(selected = group.networkRole == key, onClick = null, enabled = enabled)
        Text(stringResource(NetworkRoles.labelRes(key)), style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
  if ("services" in allowed) {
    Text(stringResource(R.string.label_group_services), style = MaterialTheme.typography.titleSmall)
    val strings = rememberStrings()
    Services.keys.forEach { key ->
      val on = key in group.services
      Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { v -> onChange { it.copy(services = if (v) it.services + key else it.services - key) } }).padding(vertical = 4.dp),
      ) {
        Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
        Text(Services.label(key, strings), style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

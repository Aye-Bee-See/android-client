package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.pluralStringResource
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.ui.common.asHeading
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.session.Role
import me.paxana.abcmailbox.data.session.SessionState

@Composable
fun AccountScreen(
  sessionState: SessionState,
  mode: EncryptionMode,
  onSignIn: () -> Unit,
  onChangePassword: () -> Unit,
  onDeleteAccount: () -> Unit = {},
  onGroupNumbers: () -> Unit = {},
  onInviteCodes: () -> Unit = {},
  onPenName: () -> Unit = {},
  viewModel: AccountViewModel = hiltViewModel(),
) {
  val ui by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(ui.notice) {
    ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() }
  }

  // Scrolls: the page outgrew a phone screen when the group's numbers and account deletion joined it, and the build
  // line (with the hidden server dialog behind it) is the last thing on it.
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(stringResource(R.string.title_account), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    when (sessionState) {
      SessionState.Loading -> Unit
      SessionState.SignedOut -> {
        Text(stringResource(R.string.account_signed_out))
        Button(onClick = onSignIn) { Text(stringResource(R.string.action_sign_in)) }
      }
      is SessionState.SignedIn -> {
        val user = sessionState.session.user
        Text(user.displayName, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.username_at, user.username), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          when (user.role) {
            Role.CHAPTER -> user.chapterId?.let { stringResource(R.string.role_member_of_group, it) } ?: stringResource(R.string.role_member_no_group)
            Role.ADMIN -> stringResource(R.string.role_admin)
            else -> stringResource(R.string.role_writer)
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        // A writer's pen name (API PR #120): what the letters are signed with, and what a prisoner writes back to.
        if (user.role == Role.USER) {
          Text(user.penName?.let { stringResource(R.string.account_pen_name, it) } ?: stringResource(R.string.account_pen_name_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account-pen-name"))
          TextButton(onClick = onPenName, modifier = Modifier.testTag("pen-name-open")) { Text(stringResource(R.string.title_pen_name)) }
        }
        HorizontalDivider()
        // For members of a group: the group's public numbers, and the one of them that a person types.
        if (user.role == Role.CHAPTER && user.chapterId != null) {
          TextButton(onClick = onInviteCodes, modifier = Modifier.testTag("invite-codes")) { Text(stringResource(R.string.title_invite_codes)) }
          TextButton(onClick = onGroupNumbers) { Text(stringResource(R.string.title_group_numbers)) }
        }
        TextButton(onClick = onChangePassword) { Text(stringResource(R.string.action_change_password)) }
        OutlinedButton(
          onClick = { viewModel.signOut(everywhere = false) },
          enabled = !ui.signingOut,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.action_sign_out))
        }
        TextButton(onClick = { viewModel.signOut(everywhere = true) }, enabled = !ui.signingOut) {
          Text(stringResource(R.string.action_sign_out_everywhere))
        }
        // Last and quiet. It opens a page that explains; nothing is deleted from here.
        TextButton(onClick = onDeleteAccount, enabled = !ui.signingOut, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
          Text(stringResource(R.string.action_delete_account))
        }
      }
    }
    // Signing out does not lose unsent letters, and people should not have to wonder.
    val unsent by hiltViewModel<me.paxana.abcmailbox.ui.letters.OutboxViewModel>().items.collectAsStateWithLifecycle()
    if (unsent.isNotEmpty()) Text(
      pluralStringResource(R.plurals.account_unsent, unsent.size, unsent.size),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Only someone signed in has a feed to be rung about.
    if (sessionState is SessionState.SignedIn) PushSetting()
    OfflineCopySection()
    Text(
      stringResource(R.string.build_line, BuildConfig.VERSION_NAME, stringResource(when (mode) { EncryptionMode.E2E -> R.string.mode_e2e; EncryptionMode.SERVER -> R.string.mode_server; EncryptionMode.UNKNOWN -> R.string.mode_unknown })) + if (ui.serverOverridden) " · ${ui.serverUrl}" else "",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      // Debug builds: five taps open the hidden server dialog.
      modifier = if (BuildConfig.DEV_TOOLS) Modifier.clickable(onClick = viewModel::onBuildLineTap) else Modifier,
    )
    if (BuildConfig.DEV_TOOLS && ui.serverDialog) {
      DevServerDialog(
        current = ui.serverUrl,
        default = ui.serverDefault,
        testServer = BuildConfig.TEST_API_BASE_URL,
        checking = ui.serverChecking,
        result = ui.serverResult,
        onSave = viewModel::saveServer,
        onReset = viewModel::resetServer,
        onDismiss = viewModel::closeServerDialog,
        onSimulatePush = viewModel::simulatePush,
      )
    }
    SnackbarHost(hostState = snackbar) { Snackbar(it) }
  }
}

package me.paxana.abcmailbox.ui.account

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.data.session.Role
import me.paxana.abcmailbox.data.session.SessionState

@Composable
fun AccountScreen(
  sessionState: SessionState,
  onSignIn: () -> Unit,
  viewModel: AccountViewModel = hiltViewModel(),
) {
  val ui by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(ui.notice) {
    ui.notice?.let { snackbar.showSnackbar(it); viewModel.noticeShown() }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Account", style = MaterialTheme.typography.headlineMedium)
    when (sessionState) {
      SessionState.Loading -> Unit
      SessionState.SignedOut -> {
        Text("You are not signed in. Browsing the directory works without an account; writing letters needs one.")
        Button(onClick = onSignIn) { Text("Sign in") }
      }
      is SessionState.SignedIn -> {
        val user = sessionState.session.user
        Text(user.displayName, style = MaterialTheme.typography.titleLarge)
        Text("@${user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          when (user.role) {
            Role.CHAPTER -> "Support group member" + (user.chapterId?.let { " (group $it)" } ?: " (no group assigned yet)")
            Role.ADMIN -> "Network admin"
            else -> "Writer"
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        OutlinedButton(
          onClick = { viewModel.signOut(everywhere = false) },
          enabled = !ui.signingOut,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Sign out")
        }
        TextButton(onClick = { viewModel.signOut(everywhere = true) }, enabled = !ui.signingOut) {
          Text("Sign out on every device")
        }
      }
    }
    Text(
      "Build ${BuildConfig.VERSION_NAME} · ${BuildConfig.ENCRYPTION_MODE} mode" + if (ui.serverOverridden) " · ${ui.serverUrl}" else "",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      // Debug builds: five taps open the hidden server dialog.
      modifier = if (BuildConfig.DEBUG) Modifier.clickable(onClick = viewModel::onBuildLineTap) else Modifier,
    )
    if (BuildConfig.DEBUG && ui.serverDialog) {
      DevServerDialog(
        current = ui.serverUrl,
        default = ui.serverDefault,
        checking = ui.serverChecking,
        result = ui.serverResult,
        onSave = viewModel::saveServer,
        onReset = viewModel::resetServer,
        onDismiss = viewModel::closeServerDialog,
      )
    }
    SnackbarHost(hostState = snackbar) { Snackbar(it) }
  }
}

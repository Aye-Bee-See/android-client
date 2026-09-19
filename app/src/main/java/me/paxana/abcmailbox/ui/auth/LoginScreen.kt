package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.ui.common.asHeading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.data.session.SessionState

/**
 * Mirrors `login.html`: username, password with a Show toggle, the note that
 * accounts come from support groups, and a pointer to recovery. The screen is
 * stateless with respect to the session: it leaves as soon as [sessionState]
 * becomes signed-in, whichever screen asked for it.
 */
@Composable
fun LoginScreen(
  sessionState: SessionState,
  onSignedIn: () -> Unit,
  onCancel: () -> Unit,
  onClaim: () -> Unit,
  onForgot: () -> Unit,
  viewModel: LoginViewModel = hiltViewModel(),
) {
  val ui by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(sessionState) {
    if (sessionState is SessionState.SignedIn) onSignedIn()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .imePadding()
      .padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Sign in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    Text(
      "Writers and support groups sign in here.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
      value = ui.username,
      onValueChange = viewModel::onUsernameChange,
      label = { Text("Username") },
      singleLine = true,
      enabled = !ui.submitting,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth().testTag("username"),
    )
    OutlinedTextField(
      value = ui.password,
      onValueChange = viewModel::onPasswordChange,
      label = { Text("Password") },
      singleLine = true,
      enabled = !ui.submitting,
      visualTransformation = if (ui.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { viewModel.onSubmit() }),
      trailingIcon = {
        TextButton(onClick = viewModel::onToggleShowPassword) { Text(if (ui.showPassword) "Hide" else "Show") }
      },
      modifier = Modifier.fillMaxWidth().testTag("password"),
    )

    ui.error?.let { message ->
      Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("error"))
    }

    Button(
      onClick = viewModel::onSubmit,
      enabled = ui.canSubmit,
      modifier = Modifier.fillMaxWidth().testTag("submit"),
    ) {
      if (ui.submitting) {
        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
      } else {
        Text("Sign in")
      }
    }

    Spacer(Modifier.height(8.dp))
    Text(
      "Don't have an account? Accounts are created by support group organizers. Ask the group you write through to set one up for you.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onClaim, modifier = Modifier.align(Alignment.Start)) { Text("I have a claim token") }
    TextButton(onClick = onForgot, modifier = Modifier.align(Alignment.Start)) { Text("Forgot your password?") }
    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.Start)) { Text("Back") }
  }
}

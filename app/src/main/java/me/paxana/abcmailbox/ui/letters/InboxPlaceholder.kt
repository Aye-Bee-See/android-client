package me.paxana.abcmailbox.ui.letters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.paxana.abcmailbox.data.session.SessionState

/** Replaced in phase 3 by the real inbox. For now it proves the signed-in gate. */
@Composable
fun InboxPlaceholder(sessionState: SessionState, onSignIn: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Inbox", style = MaterialTheme.typography.headlineMedium)
    when (sessionState) {
      SessionState.Loading -> Unit
      SessionState.SignedOut -> {
        Text("Sign in to see your conversations and write letters.")
        Button(onClick = onSignIn) { Text("Sign in") }
      }
      is SessionState.SignedIn -> {
        Text("Signed in as ${sessionState.session.user.displayName}.")
        Text("Your conversations arrive in the next build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

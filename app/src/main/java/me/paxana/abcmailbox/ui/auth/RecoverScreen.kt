package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.SectionTitle

/**
 * After `forgot-password.html`, corrected for what the API actually offers.
 * In server mode there is no self-service recovery, so this page explains the
 * two real paths. The recovery-code flow belongs to end-to-end mode (phase 5).
 */
@Composable
fun RecoverScreen(onBack: () -> Unit, onClaim: () -> Unit) {
  DetailScaffold(title = "Forgot your password?", onBack = onBack) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("There is no reset link we can email you. That is deliberate: a server that can reset your password is a server that can get into your letters.", style = MaterialTheme.typography.bodyLarge)

      SectionTitle("If a support group set up your account and you have not claimed it yet")
      Text("Ask the group for a new claim token. Tokens last 72 hours and work once; they can make another at any time.", style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onClaim) { Text("I have a claim token") }

      SectionTitle("If the account is your own")
      Text(
        if (BuildConfig.ENCRYPTION_MODE == "e2e") {
          "Use the recovery code you saved when you set your password. Recovery with a code arrives in this app with end-to-end encryption."
        } else {
          "Contact a network admin through the group you write with. They can confirm who you are and set a new password for you."
        },
        style = MaterialTheme.typography.bodyMedium,
      )
      Text("Too many wrong sign-in attempts lock a username for 15 minutes. Waiting is sometimes all it takes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

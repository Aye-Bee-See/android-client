package me.paxana.abcmailbox.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.ui.common.AlertBanner

/**
 * A claim link or an invite-code slip opened while an account is signed in (Copilot's review of PR #5): making
 * or taking over an account would replace the session without a word. The person is told, and chooses.
 */
@Composable
fun SignedInNotice(padding: PaddingValues, text: String, onSignOut: () -> Unit, onBack: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    AlertBanner(text)
    Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth().testTag("notice-sign-out")) { Text(stringResource(R.string.action_sign_out)) }
    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
  }
}

package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.domain.PasswordRules

/**
 * Four bars and a word. The bars are decoration for the word (a screen reader gets the sentence only), and the
 * word is advice: nothing stops a person choosing a weak password beyond the length rule the network decided on.
 */
@Composable
fun PasswordStrengthMeter(password: String, modifier: Modifier = Modifier) {
  if (password.isEmpty()) return
  val strength = PasswordRules.strength(password)
  val colour = when (strength) {
    PasswordRules.Strength.WEAK -> MaterialTheme.colorScheme.error
    PasswordRules.Strength.FAIR -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
  }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      repeat(4) { i ->
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < strength.bars) colour else MaterialTheme.colorScheme.surfaceVariant))
      }
    }
    Text(stringResource(R.string.strength_label, stringResource(strength.labelRes)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

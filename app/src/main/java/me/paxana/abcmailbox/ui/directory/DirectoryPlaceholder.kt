package me.paxana.abcmailbox.ui.directory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Replaced in phase 2 by the prisoners, facilities, and groups lists. */
@Composable
fun DirectoryPlaceholder() {
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Letters matter.", style = MaterialTheme.typography.displaySmall)
    Text("Write one today.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
    Text(
      "Browse prisoner profiles, find a support group near you, and learn exactly what each facility requires before you write.",
      style = MaterialTheme.typography.bodyLarge,
    )
    Text("The directory arrives in the next build.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

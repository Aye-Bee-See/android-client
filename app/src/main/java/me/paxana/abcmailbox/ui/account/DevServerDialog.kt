package me.paxana.abcmailbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * The hidden developer dialog: reached by tapping the build line on the
 * Account tab five times, debug builds only. Lets a phone on the same Wi-Fi
 * point at the API on a development machine.
 */
@Composable
fun DevServerDialog(
  current: String,
  default: String,
  checking: Boolean,
  result: String?,
  onSave: (String) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf(current) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("API server") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Debug builds only. Enter your computer's address on this Wi-Fi, for example 192.168.1.20 (port 3000 is assumed). Saving signs you out.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("Base URL") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth(),
        )
        Text("Default: $default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Reachable")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(text) }, enabled = !checking) { Text(if (checking) "Checking…" else "Save and check") } },
    dismissButton = {
      Column {
        // Show the address now in force; the field is local state and would otherwise keep the old one.
        TextButton(onClick = { text = default; onReset() }, enabled = !checking) { Text("Use default") }
        TextButton(onClick = onDismiss) { Text("Close") }
      }
    },
  )
}

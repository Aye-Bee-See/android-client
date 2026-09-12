package me.paxana.abcmailbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import me.paxana.abcmailbox.ui.nav.AppShell
import me.paxana.abcmailbox.ui.theme.AbcTheme

/**
 * The only Activity. Everything else is a composable inside [AppShell];
 * `@AndroidEntryPoint` lets Hilt provide ViewModels to those composables.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AbcTheme {
        AppShell()
      }
    }
  }
}

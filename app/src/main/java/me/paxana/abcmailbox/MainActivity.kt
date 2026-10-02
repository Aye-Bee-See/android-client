package me.paxana.abcmailbox

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dagger.hilt.android.AndroidEntryPoint
import me.paxana.abcmailbox.data.dev.DevServerUrl
import me.paxana.abcmailbox.ui.common.LocalApiBase
import me.paxana.abcmailbox.ui.nav.AppShell
import me.paxana.abcmailbox.ui.theme.AbcTheme
import javax.inject.Inject

/**
 * The only Activity. Everything else is a composable inside [AppShell];
 * `@AndroidEntryPoint` lets Hilt provide ViewModels to those composables.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  /** The API address in use, for pictures the API hosts (the image loader has its own HTTP client). */
  @Inject lateinit var apiBase: DevServerUrl

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // Nothing in this app belongs in the Recents thumbnail, which is shown to whoever holds the phone: letters, codes,
    // who is written to. Screenshots stay the person's own to take (the recovery codes say to save them somewhere).
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
    setContent {
      AbcTheme {
        CompositionLocalProvider(LocalApiBase provides apiBase::current) { AppShell() }
      }
    }
  }
}

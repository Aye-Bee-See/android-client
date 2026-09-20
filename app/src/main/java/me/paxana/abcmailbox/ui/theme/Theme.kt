package me.paxana.abcmailbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
  // The snackbar is drawn in the "inverse" colours: a dark bar on the light theme. Unset, they are Material's
  // purple-grey and lavender. The action's red is the lighter one, 5.3:1 on ink.
  inverseSurface = Ink,
  inverseOnSurface = Paper,
  inversePrimary = RedOnWashDark,
  primary = Ink,
  onPrimary = Paper,
  secondary = Red,
  onSecondary = PaperRaised,
  // The bottom bar's selected pill and other "container" surfaces; without
  // these Material 3 falls back to its default purple.
  secondaryContainer = RedWash,
  onSecondaryContainer = Red,
  surfaceContainer = PaperRaised,
  surfaceContainerLow = PaperRaised,
  surfaceContainerHigh = PaperRaised,
  tertiary = InkMuted,
  background = Paper,
  onBackground = Ink,
  surface = Paper,
  onSurface = Ink,
  surfaceVariant = PaperRaised,
  onSurfaceVariant = InkMuted,
  outline = Rule,
  outlineVariant = Rule,
  error = Red,
  onError = PaperRaised,
  errorContainer = RedWash,
  onErrorContainer = Red,
)

private val DarkColors = darkColorScheme(
  inverseSurface = InkDark, // a light bar on the dark theme
  inverseOnSurface = PaperDark,
  inversePrimary = Red, // 4.9:1 on the light bar
  primary = InkDark,
  onPrimary = PaperDark,
  secondary = RedDark,
  onSecondary = PaperDark,
  secondaryContainer = RedWashDark,
  onSecondaryContainer = RedOnWashDark,
  surfaceContainer = PaperRaisedDark,
  surfaceContainerLow = PaperRaisedDark,
  surfaceContainerHigh = PaperRaisedDark,
  tertiary = InkMutedDark,
  background = PaperDark,
  onBackground = InkDark,
  surface = PaperDark,
  onSurface = InkDark,
  surfaceVariant = PaperRaisedDark,
  onSurfaceVariant = InkMutedDark,
  outline = RuleDark,
  outlineVariant = RuleDark,
  error = RedDark,
  onError = PaperDark,
  errorContainer = RedWashDark,
  onErrorContainer = RedDark,
)

/**
 * The scaffold's theme used Material's dynamic colour (wallpaper-derived on
 * Android 12+). It is switched off here on purpose: the app should look like
 * the site, not like the wallpaper.
 */
@Composable
fun AbcTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = AbcTypography,
    content = content,
  )
}

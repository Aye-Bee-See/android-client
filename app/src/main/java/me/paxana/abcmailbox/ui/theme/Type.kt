package me.paxana.abcmailbox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Serif for headings, as on the site (Georgia there; the platform serif here),
// the default sans for everything read at length.
private val Serif = FontFamily.Serif

val AbcTypography = Typography(
  displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp),
  headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
  headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp),
  titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 26.sp),
  titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
  bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
  bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
  labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.3.sp),
  labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp),
)

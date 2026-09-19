package me.paxana.abcmailbox.ui.common

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import java.time.format.FormatStyle
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Built per call, because the language can change while the app runs. English keeps the day-first form the
// web site uses; other languages get their own conventions ("19 de septiembre de 2026", "19 сентября 2026 г.").
private val english get() = Locale.getDefault().language == "en"
private val longDate get() = if (english) DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH) else DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
private val shortDate get() = if (english) DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH) else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
private val shortDateTimeFormat get() = if (english) DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())

fun LocalDate.long(): String = format(longDate)
fun Instant.shortDate(): String = atZone(ZoneId.systemDefault()).toLocalDate().format(shortDate)
fun Instant.shortDateTime(): String = atZone(ZoneId.systemDefault()).format(shortDateTimeFormat)
fun Instant.longDate(): String = atZone(ZoneId.systemDefault()).toLocalDate().format(longDate)

/** "Verified 12 Jun 2026" or "Not yet verified", plus how long ago when it matters. */
fun verificationLine(at: Instant?, strings: Strings): String {
  if (at == null) return strings.get(R.string.verified_never)
  val months = ChronoUnit.MONTHS.between(at.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()).toInt()
  return if (months <= 0) strings.get(R.string.verified_this_month, at.shortDate()) else strings.plural(R.plurals.verified_months_ago, months, at.shortDate())
}

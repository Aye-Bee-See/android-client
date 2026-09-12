package me.paxana.abcmailbox.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val shortDate = DateTimeFormatter.ofPattern("d MMM yyyy")

fun LocalDate.long(): String = format(longDate)
fun Instant.shortDate(): String = atZone(ZoneId.systemDefault()).toLocalDate().format(shortDate)

/** "Verified 12 Jun 2026" or "Not yet verified", plus how long ago when it matters. */
fun verificationLine(at: Instant?): String {
  if (at == null) return "Not yet verified"
  val months = ChronoUnit.MONTHS.between(at.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
  val ago = when {
    months <= 0 -> "this month"
    months == 1L -> "1 month ago"
    else -> "$months months ago"
  }
  return "Verified ${at.shortDate()} ($ago)"
}

package me.paxana.abcmailbox.data.api

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings

/**
 * [AppError.userMessage] in the reader's language where the app can do better than the server's English. A `429`
 * (API PR #128: writes are counted per account per hour, as sign-ins are per address) is worded here with the wait
 * from `Retry-After`, never as the server's "Try again in 45 minute(s)."; everything else is the server's sentence.
 */
fun AppError.message(strings: Strings): String? = when (this) {
  is AppError.RateLimited -> retryAfterSeconds?.let { s ->
    val minutes = ((s + 59) / 60).coerceAtLeast(1).toInt()
    strings.plural(R.plurals.error_rate_limited_wait, minutes, minutes)
  } ?: strings.get(R.string.error_rate_limited_later)
  is AppError.WrongEncryptionMode -> strings.get(R.string.error_wrong_encryption_mode)
  // API #171: said in the reader's language; the server's sentence names the group by its number.
  is AppError.Forbidden -> if (isGroupBlock) strings.get(R.string.error_group_block) else userMessage
  is AppError.NotFound -> if (condition == AppError.NotFound.PRISONER_GONE) strings.get(R.string.error_prisoner_gone) else userMessage
  else -> userMessage
}

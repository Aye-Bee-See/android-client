package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings

/**
 * One entry of the account's notification feed. The server sends an event name and ids; what it *says*
 * is decided here, on the phone, in the user's language. The sentences name nobody and quote nothing,
 * because they end up on lock screens: the names are inside the app, behind the phone's lock.
 */
data class Activity(val id: Int, val kind: Kind, val chatId: Int?, val messageId: Int?) {
  enum class Kind { REPLY, PRINTED, MAILED, QUEUED_FOR_GROUP, CHANGE_APPROVED, CHANGE_REJECTED, OTHER }

  fun sentence(strings: Strings): String = strings.get(
    when (kind) {
      Kind.REPLY -> R.string.activity_reply
      Kind.PRINTED -> R.string.activity_printed
      Kind.MAILED -> R.string.activity_mailed
      Kind.QUEUED_FOR_GROUP -> R.string.activity_queued
      Kind.CHANGE_APPROVED -> R.string.activity_change_approved
      Kind.CHANGE_REJECTED -> R.string.activity_change_rejected
      // An event this version has never heard of still deserves a ring: the app will show whatever it is.
      Kind.OTHER -> R.string.activity_other
    }
  )

  companion object {
    fun kindOf(event: String, status: String?): Kind = when (event) {
      "letter.reply" -> Kind.REPLY
      "letter.status" -> when (status) { "printed" -> Kind.PRINTED; "mailed" -> Kind.MAILED; else -> Kind.OTHER }
      "letter.queued" -> Kind.QUEUED_FOR_GROUP
      "submission.decided" -> when (status) { "approved" -> Kind.CHANGE_APPROVED; "rejected" -> Kind.CHANGE_REJECTED; else -> Kind.OTHER }
      else -> Kind.OTHER
    }
  }
}

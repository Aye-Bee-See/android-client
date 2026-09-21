package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.text.Strings

/**
 * One entry of the account's notification feed. The server sends an event name and ids; what it *says*
 * is decided here, on the phone, in the user's language. The sentences name nobody and quote nothing,
 * because they end up on lock screens: the names are inside the app, behind the phone's lock.
 */
data class Activity(
  val id: Int, val kind: Kind, val chatId: Int?, val messageId: Int?,
  /** With `MOVED` and `FREED`: how many of this person's queued letters to that prisoner are waiting for them now. */
  val held: Int = 0,
  /**
   * How many letters this entry is about. A group that marks thirty letters on a letter night tells each writer once
   * (API PR #111): `messageId` is then null, and `chatId` is set only if the letters share a conversation.
   */
  val count: Int = 1,
) {
  enum class Kind { REPLY, PRINTED, MAILED, RETURNED, MOVED, FREED, QUEUED_FOR_GROUP, CHANGE_APPROVED, CHANGE_REJECTED, OTHER }

  /** Something the person has to do, not only know. These share the replies' channel: a letter that waits for its writer goes nowhere until they look. */
  val needsThem: Boolean get() = kind == Kind.RETURNED || ((kind == Kind.MOVED || kind == Kind.FREED) && held > 0)

  fun sentence(strings: Strings): String = base(strings) + if ((kind == Kind.MOVED || kind == Kind.FREED) && held > 0) " " + strings.plural(R.plurals.activity_waiting, held) else ""

  private fun base(strings: Strings): String {
    if (count > 1) when (kind) {
      Kind.PRINTED -> return strings.plural(R.plurals.activity_printed_many, count)
      Kind.MAILED -> return strings.plural(R.plurals.activity_mailed_many, count)
      Kind.RETURNED -> return strings.plural(R.plurals.activity_returned_many, count)
      else -> Unit
    }
    return single(strings)
  }

  private fun single(strings: Strings): String = strings.get(
    when (kind) {
      Kind.REPLY -> R.string.activity_reply
      Kind.PRINTED -> R.string.activity_printed
      Kind.MAILED -> R.string.activity_mailed
      Kind.RETURNED -> R.string.activity_returned
      Kind.MOVED -> R.string.activity_moved
      Kind.FREED -> R.string.activity_freed
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
      "letter.status" -> when (status) { "printed" -> Kind.PRINTED; "mailed" -> Kind.MAILED; "returned" -> Kind.RETURNED; else -> Kind.OTHER }
      "prisoner.moved" -> Kind.MOVED
      // The API sends this event for `free` only, today. Any other status it may one day announce gets the cautious sentence.
      "prisoner.status" -> if (status == "free") Kind.FREED else Kind.OTHER
      "letter.queued" -> Kind.QUEUED_FOR_GROUP
      "submission.decided" -> when (status) { "approved" -> Kind.CHANGE_APPROVED; "rejected" -> Kind.CHANGE_REJECTED; else -> Kind.OTHER }
      else -> Kind.OTHER
    }
  }
}

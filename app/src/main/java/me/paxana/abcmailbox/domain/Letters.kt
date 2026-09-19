package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.annotation.StringRes
import java.time.Instant

enum class LetterStatus(val key: String, @StringRes val labelRes: Int, @StringRes val meaningRes: Int?) {
  QUEUED("queued", R.string.status_queued, R.string.status_queued_meaning),
  PRINTED("printed", R.string.status_printed, R.string.status_printed_meaning),
  MAILED("mailed", R.string.status_mailed, R.string.status_mailed_meaning),
  RECEIVED("received", R.string.status_received, R.string.status_received_meaning),
  UNKNOWN("", R.string.status_unknown, null);

  companion object {
    fun fromKey(key: String?): LetterStatus = entries.firstOrNull { it.key == key && key.isNotEmpty() } ?: UNKNOWN
  }
}

data class Attachment(val id: Int, val messageId: Int, val name: String, val mimeType: String, val size: Long, val nonce: String? = null) {
  val sizeLabel: String get() = when {
    size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
    size >= 1024 -> "${size / 1024} KB"
    else -> "$size B"
  }
}

data class StatusChange(val from: LetterStatus?, val to: LetterStatus, val at: Instant?, val byUserId: Int?)

data class Letter(
  val id: Int,
  val threadId: Int?,
  val prisonerId: Int? = null,
  val writerId: Int? = null,
  val fromPrisoner: Boolean,
  val status: LetterStatus,
  val body: String,
  val relayNote: String?,
  val relayGroupId: Int?,
  val relayGroupName: String?,
  val keep: Boolean,
  val createdAt: Instant?,
  val statusChangedAt: Instant?,
  val history: List<StatusChange>,
  val attachments: List<Attachment>,
  /** End-to-end mode: true when this device holds no key that opens the letter. */
  val locked: Boolean = false,
) {
  /** The brief's rule: a writer may edit or delete only while the letter is queued. */
  val canEdit: Boolean get() = !fromPrisoner && status == LetterStatus.QUEUED
}

data class LastMessage(val id: Int, val fromPrisoner: Boolean, val status: LetterStatus, val at: Instant?, val preview: String?)

data class Thread(
  val id: Int,
  val prisonerId: Int,
  val prisoner: Prisoner?,
  val lastMessage: LastMessage?,
  val lastActivity: Instant?,
  val letters: List<Letter>,
  /** The account on the writer's side; groups use it to label threads and to know if they may write in them. */
  val writer: ThreadWriter? = null,
) {
  fun title(strings: Strings): String = prisoner?.name ?: strings.get(R.string.prisoner_numbered, prisonerId)
}

/** How a letter to this facility will be routed, worked out before sending so the writer sees it. */
sealed interface RelayChoice {
  /** Exactly one relay group: the server will pick it; show it. */
  data class Automatic(val group: Group) : RelayChoice
  /** No relay group and the facility accepts direct mail. */
  data object Direct : RelayChoice
  /** Several relay groups: the writer may (or, for relay-only facilities, must) choose. */
  data class Choose(val options: List<Group>, val required: Boolean) : RelayChoice
  /** Relay-only facility with no relay group listed: nothing can be sent yet. */
  data class Blocked(val facilityName: String) : RelayChoice
}

/** Mirrors the API's resolution rules (README, "Relay group") so the UI can explain them up front. */
fun resolveRelay(facility: Facility?): RelayChoice {
  if (facility == null) return RelayChoice.Direct
  val groups = facility.relayGroups.filter { it.accountStatus == null || it.accountStatus == "active" }
  val relayOnly = facility.routing == Routing.RELAY_ONLY
  return when (groups.size) {
    0 -> if (relayOnly) {
      RelayChoice.Blocked(facility.name)
    } else {
      RelayChoice.Direct
    }
    1 -> RelayChoice.Automatic(groups.first())
    else -> RelayChoice.Choose(groups, required = relayOnly)
  }
}

/** A rough printed-page estimate for the counter under the editor; one typed page is about 3,000 characters. */
fun estimatePages(characters: Int): Int = if (characters <= 0) 1 else ((characters + 2_999) / 3_000)

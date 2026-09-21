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
  /** The post brought it back (API PR #105). From `mailed` only, and final. Why is in [Letter.returnReason]. */
  RETURNED("returned", R.string.status_returned, R.string.status_returned_meaning),
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

/** `reason` and `note` come with a move to `returned` only. The note is what the envelope said, in the group's words; it is never encrypted. */
data class StatusChange(val from: LetterStatus?, val to: LetterStatus, val at: Instant?, val byUserId: Int?, val reason: ReturnReason? = null, val note: String? = null)

/**
 * Why a letter came back. The server sends a code and the phone chooses the words, so they can be in the
 * reader's language. [adviceRes] is what the writer can do about it: a return is only useful if it says that.
 */
enum class ReturnReason(val key: String, @StringRes val labelRes: Int, @StringRes val adviceRes: Int, /** The short form a group member picks from, envelope in hand. */ @StringRes val choiceRes: Int) {
  REFUSED("refused", R.string.return_refused, R.string.return_refused_advice, R.string.return_choice_refused),
  RULE_VIOLATION("rule_violation", R.string.return_rule_violation, R.string.return_rule_violation_advice, R.string.return_choice_rule_violation),
  TRANSFERRED("transferred", R.string.return_transferred, R.string.return_transferred_advice, R.string.return_choice_transferred),
  RELEASED("released", R.string.return_released, R.string.return_released_advice, R.string.return_choice_released),
  BAD_ADDRESS("bad_address", R.string.return_bad_address, R.string.return_bad_address_advice, R.string.return_choice_bad_address),
  UNKNOWN("unknown", R.string.return_unknown, R.string.return_unknown_advice, R.string.return_choice_unknown);

  /** The API's rule: these three put the directory's address for the person in doubt, for staff to look at. */
  val putsAddressInDoubt: Boolean get() = this == TRANSFERRED || this == RELEASED || this == BAD_ADDRESS

  companion object {
    /** The groups may be asked for more reasons than these six. One this version has not heard of is still a return: say so, without a why. */
    fun fromKey(key: String?): ReturnReason? = if (key.isNullOrEmpty()) null else entries.firstOrNull { it.key == key } ?: UNKNOWN
    /** What a group member can choose from, in the order the API lists them. */
    val choices: List<ReturnReason> get() = entries
  }
}

/**
 * Why a queued letter is waiting (API PR #106). A hold is not a status: the letter is still `queued`, and
 * nothing prints it by oversight. Each reason has its own way out, and only one of them is the writer's to take.
 */
enum class HeldReason(val key: String, /** One line for the group's queue. */ @StringRes val queueNoticeRes: Int, /** The group's view of it, on the letter's own page. */ @StringRes val groupTextRes: Int) {
  /** They were moved to a facility that only takes relayed mail and has several relay groups, or none. The writer picks one. */
  CHOOSE_RELAY("choose_relay", R.string.held_queue_choose_relay, R.string.held_group_choose_relay),
  /** End-to-end mode: sealed to a group that does not serve the new facility. The writer's phone sends it again. */
  RESEAL_NEEDED("reseal_needed", R.string.held_queue_reseal_needed, R.string.held_group_reseal_needed),
  /** They were freed. The group may print it on purpose, the writer may delete it, or the directory is corrected. */
  PRISONER_FREE("prisoner_free", R.string.held_queue_prisoner_free, R.string.held_group_prisoner_free),
  /** A hold this version has never heard of. Still a hold: the letter must not look as if it were on its way. */
  OTHER("", R.string.held_queue_other, R.string.held_group_other);

  companion object { fun fromKey(key: String?): HeldReason? = if (key.isNullOrEmpty()) null else entries.firstOrNull { it.key == key } ?: OTHER }
}

/** A letter sent to replace one that came back. */
data class Resent(val id: Int, val status: LetterStatus, val at: Instant?)

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
  /** Set when [status] is `RETURNED`. */
  val returnReason: ReturnReason? = null,
  /** Set while a queued letter is held. */
  val heldReason: HeldReason? = null,
  /** The returned letter this one replaces. */
  val resendOfId: Int? = null,
  /** On a returned letter: what was sent in its place. Only on a full read. */
  val resentAs: List<Resent> = emptyList(),
) {
  val isHeld: Boolean get() = heldReason != null && status == LetterStatus.QUEUED
  /** What the envelope said when it came back, if the group wrote it down. It lives on the history row, so only a full read has it. */
  val returnNote: String? get() = history.lastOrNull { it.to == LetterStatus.RETURNED }?.note?.takeIf { it.isNotBlank() }
  val returnedAt: Instant? get() = history.lastOrNull { it.to == LetterStatus.RETURNED }?.at ?: statusChangedAt.takeIf { status == LetterStatus.RETURNED }
  /** Offered once: a letter already sent again shows what replaced it. Not for a letter this device cannot open, which has no text to send. */
  val canSendAgain: Boolean get() = !fromPrisoner && status == LetterStatus.RETURNED && resentAs.isEmpty() && !locked
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

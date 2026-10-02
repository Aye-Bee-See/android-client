package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.data.api.message
import me.paxana.abcmailbox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.db.OutboxDao
import me.paxana.abcmailbox.data.db.OutboxEntity
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OutboxAttachment(val path: String, val name: String, val mimeType: String, val size: Long, /** Repeated on every retry of this upload. */ val key: String = UUID.randomUUID().toString())

/** Everything about a queued letter that must not be readable on disk. Stored as one encrypted JSON blob. */
@Serializable
data class OutboxPayload(
  val prisonerId: Int,
  val prisonerName: String,
  /** A group member writing for someone: the name shown as "Writing as". */
  val writingAs: String? = null,
  val body: String,
  val relayNote: String? = null,
  val relayChapter: Int? = null,
  val asWriterId: Int? = null,
  val fromPrisoner: Boolean = false,
  val groupRelaysFacility: Boolean = false,
  val attachments: List<OutboxAttachment> = emptyList(),
  /** Why the server refused, in its own words. */
  val problem: String? = null,
  /**
   * Made when the letter is queued (or carried over from the compose screen's failed attempt) and sent with
   * every try. It is what lets the server answer "I already have that one" instead of mailing a second copy.
   */
  val idempotencyKey: String,
  // Both optional with defaults, so payloads queued by an older version of the app still open.
  val resendOf: Int? = null,
  val replacesHeld: Int? = null,
  val reference: String? = null,
  /**
   * How many times the server, or the connection, answered this letter with a fault rather than a refusal (a 5xx, an
   * answer that could not be read). Not "no answer at all": a week without a connection counts for nothing. Past
   * [MAX_FAULTS] the letter is set aside as refused with the last fault's words, so the person sees it and the letters
   * behind it can go; "try as it is" starts the count again.
   */
  val faults: Int = 0,
) {
  companion object { const val MAX_FAULTS = 5 }
  fun toNewLetter() = NewLetter(prisonerId, body, relayNote, relayChapter, asWriterId, fromPrisoner, groupRelaysFacility, idempotencyKey, resendOf, reference = reference, replacesHeld = replacesHeld)
}

/** A queued letter as screens see it. */
data class OutboxItem(
  val id: Long,
  val payload: OutboxPayload,
  val queuedAt: Instant,
  /** Null while waiting. Otherwise the server's reason, and the letter needs the writer's attention. */
  val problem: String?,
  /** The server has the letter; only files are outstanding or were refused. */
  val letterWasSent: Boolean,
)

/**
 * [limitedUntil]: the server is pacing this account's writes (a `429`) and asked for nothing before then.
 * [tryAgain]: a letter stopped the run for a reason time may mend (no connection, a server fault, an earlier try still
 * in flight), so the worker should come back by itself. False when what is waiting waits for a person: a sign-in, the
 * password for the keys, or an editor to close. Those book a run of their own when they happen, and a worker told to
 * retry meanwhile would only back off further, holding up the run they book.
 */
data class FlushOutcome(val sent: Int = 0, val refused: Int = 0, val stillWaiting: Int = 0, val limitedUntil: Instant? = null, val tryAgain: Boolean = false)

/** Arranges for [OutboxRepository.flush] to run when there is a network, even if the app is closed by then. */
interface OutboxScheduler {
  fun schedule()
  /** One run no earlier than [at] (the end of a `429`'s `Retry-After`), whatever else is scheduled meanwhile. */
  fun scheduleAt(at: Instant)
}

interface OutboxRepository {
  /** The signed-in account's queued letters, oldest first. Empty when signed out. */
  fun items(): Flow<List<OutboxItem>>
  suspend fun queue(prisonerName: String, writingAs: String?, letter: NewLetter, attachments: List<StagedFile>): Long
  /**
   * For editing: the letter, with its files decrypted back into staging. From here until [release] (or until it is
   * deleted) the letter is held back from sending: the copy on the compose screen and the copy in the queue must not
   * both go. Null when there is nothing to edit any more: the row is gone, or the server already has the letter.
   */
  suspend fun open(id: Long): Pair<OutboxPayload, List<StagedFile>>?
  /** The compose screen that opened the letter is no longer showing: if the letter is still queued, it may be sent again. */
  fun release(id: Long) {}
  /**
   * The compose screen is showing again after [release]: the letter is held back once more. False when it went in
   * the meantime (or was deleted), and then the screen must not send what it has: that would be a second letter.
   */
  suspend fun hold(id: Long): Boolean = true
  suspend fun delete(id: Long)
  /** After [open] and a successful re-send: drops the row and its encrypted files, which the new send has superseded. */
  suspend fun forget(id: Long) = delete(id)
  /** Puts a refused letter back in line, unchanged (the reason may have been temporary: a suspended group, say). */
  suspend fun retry(id: Long)
  /** Sends what can be sent now. Safe to call at any time and from anywhere; runs one at a time. */
  suspend fun flush(): FlushOutcome

  /**
   * Until when the server asked this account to send nothing more (a `429` on a write, API PR #128, with its
   * `Retry-After`); null when it may send. Kept in memory only: after a restart the next try simply asks again, and
   * a refused request does not count against the limit.
   */
  val limitedUntil: StateFlow<Instant?>

  /** A `429` seen outside the outbox (compose sending while online): the outbox waits it out before asking again. */
  fun waitOut(retryAfterSeconds: Long?)
  suspend fun hasWaiting(): Boolean
  /** The account was deleted: its unsent letters and their files go too. Takes the id, because by then nobody is signed in. Answers how many letters went. */
  suspend fun eraseFor(userId: Int): Int = 0
}

/**
 * Letters written without a connection.
 *
 * The rule that shapes everything here is that a prisoner must never get the same letter twice.
 * Every queued letter, and every file with it, carries an `Idempotency-Key` made once and repeated on
 * each retry (API PR #97): if an earlier attempt did arrive, the server hands back that letter instead
 * of creating another. The letter and its files are still separate steps, each recorded the moment it
 * succeeds, so a retry resumes where the last one stopped.
 *
 * (Before the API had keys, this class compared writer, time and text to guess whether a letter had
 * arrived. That guess was blind in end-to-end mode. It is gone.)
 *
 * In end-to-end mode a letter cannot be sealed offline (sealing needs the relay group's current
 * public key), so it waits here encrypted under the phone's Keystore key, as drafts do, and is
 * sealed when it is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultOutboxRepository @Inject constructor(
  private val dao: OutboxDao,
  private val cipher: SecretCipher,
  private val files: LocalFilesContract,
  private val letters: LettersRepository,
  private val codec: LetterCodec,
  private val sessions: SessionRepository,
  private val scheduler: OutboxScheduler,
  private val json: Json,
  private val strings: Strings,
) : OutboxRepository {

  private val flushing = Mutex()
  // Letters open on a compose screen. In memory only: if the app dies with one open, it simply goes back in line.
  private val beingEdited = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

  /** The clock, replaceable in tests. */
  internal var now: () -> Instant = Instant::now
  private val _limitedUntil = MutableStateFlow<Instant?>(null)
  override val limitedUntil: StateFlow<Instant?> = _limitedUntil.asStateFlow()

  // Without a Retry-After, a minute: long enough not to hammer, short enough not to strand a letter night.
  override fun waitOut(retryAfterSeconds: Long?) {
    val until = now().plusSeconds((retryAfterSeconds ?: 60L).coerceAtLeast(1L))
    _limitedUntil.value = until
    scheduler.scheduleAt(until)
  }
  private val myId: Int? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.id

  private fun seal(payload: OutboxPayload): String = Base64.getEncoder().encodeToString(cipher.encrypt(json.encodeToString(OutboxPayload.serializer(), payload).toByteArray()))
  private fun unseal(row: OutboxEntity): OutboxPayload? = runCatching { json.decodeFromString(OutboxPayload.serializer(), String(cipher.decrypt(Base64.getDecoder().decode(row.sealed)))) }.getOrNull()

  override fun items(): Flow<List<OutboxItem>> = sessions.state.flatMapLatest { state ->
    val id = (state as? SessionState.SignedIn)?.session?.user?.id ?: return@flatMapLatest flowOf(emptyList())
    dao.observe(id).map { rows -> rows.mapNotNull { row -> unseal(row)?.let { p -> OutboxItem(row.id, p, Instant.ofEpochMilli(row.queuedAt), p.problem.takeIf { row.state == OutboxEntity.STATE_REFUSED }, row.messageId != null) } } }
  }

  override suspend fun hasWaiting(): Boolean = dao.countWaiting() > 0

  override suspend fun queue(prisonerName: String, writingAs: String?, letter: NewLetter, attachments: List<StagedFile>): Long {
    val userId = checkNotNull(myId) { "only a signed-in account can queue a letter" }
    val stored = withContext(Dispatchers.IO) {
      attachments.map { staged ->
        // Out of the cache (which Android may empty) and encrypted, because it may sit here for days.
        val target = files.newOutboxFile()
        target.writeBytes(cipher.encrypt(staged.file.readBytes()))
        files.discard(staged)
        OutboxAttachment(target.path, staged.name, staged.mimeType, staged.size)
      }
    }
    // The compose screen's key if it already tried with one: that attempt may have arrived, and the same key is how the server will know.
    val payload = OutboxPayload(letter.prisonerId, prisonerName, writingAs, letter.body, letter.relayNote, letter.relayChapter, letter.asWriterId, letter.fromPrisoner, letter.groupRelaysFacility, stored, resendOf = letter.resendOf, replacesHeld = letter.replacesHeld, reference = letter.reference, idempotencyKey = letter.idempotencyKey ?: UUID.randomUUID().toString())
    return dao.insert(OutboxEntity(userId = userId, sealed = seal(payload), queuedAt = System.currentTimeMillis())).also { scheduler.schedule() }
  }

  override suspend fun open(id: Long): Pair<OutboxPayload, List<StagedFile>>? = flushing.withLock { // not while a flush is half way through sending it
    // A letter the server already has is not opened: sent again from the compose screen it would be a second letter.
    val row = dao.get(id)?.takeIf { it.userId == myId && it.messageId == null } ?: return@withLock null
    val payload = unseal(row) ?: return@withLock null
    beingEdited += id
    payload to withContext(Dispatchers.IO) { payload.attachments.mapNotNull(::unsealFile) }
  }

  override fun release(id: Long) { if (beingEdited.remove(id)) scheduler.schedule() }

  override suspend fun hold(id: Long): Boolean = flushing.withLock {
    (dao.get(id)?.takeIf { it.userId == myId && it.messageId == null } != null).also { if (it) beingEdited += id }
  }

  private fun unsealFile(a: OutboxAttachment): StagedFile? = runCatching {
    val plain = files.newStagingFile(a.name).apply { writeBytes(cipher.decrypt(File(a.path).readBytes())) }
    StagedFile(plain, a.name, a.mimeType, a.size)
  }.getOrNull()

  override suspend fun delete(id: Long) {
    val row = dao.get(id)?.takeIf { it.userId == myId } ?: return
    unseal(row)?.attachments?.forEach { File(it.path).delete() }
    // The row first, the hold after: a flush under way looks at the hold and then at the row (see there), so whichever
    // moment it looks, one of the two tells it this letter is not to be sent.
    dao.delete(id)
    beingEdited -= id
  }

  override suspend fun eraseFor(userId: Int): Int = flushing.withLock { // not while the worker is half way through sending one of them
    val rows = dao.allFor(userId)
    rows.forEach { row -> unseal(row)?.attachments?.forEach { File(it.path).delete() } }
    dao.deleteFor(userId)
    // A row that could not be opened cannot name its files. If nobody else has letters waiting, nothing in the folder is needed.
    if (dao.countAll() == 0) files.emptyOutboxFolder()
    rows.size
  }

  override suspend fun retry(id: Long) {
    val row = dao.get(id)?.takeIf { it.userId == myId } ?: return
    val payload = unseal(row) ?: return
    dao.update(row.copy(state = OutboxEntity.STATE_WAITING, sealed = seal(payload.copy(problem = null))))
    scheduler.schedule()
  }

  override suspend fun flush(): FlushOutcome = flushing.withLock {
    val userId = myId ?: return FlushOutcome(stillWaiting = dao.countWaiting())
    // Asked to wait: every trigger (the network returning, the app opening, "Send now") waits it out rather than asking again.
    _limitedUntil.value?.let { until ->
      if (until.isAfter(now())) return FlushOutcome(stillWaiting = dao.waiting(userId).size, limitedUntil = until)
      _limitedUntil.value = null
    }
    var sent = 0; var refused = 0; var tryAgain = false
    val unreadable = mutableSetOf<Long>()
    for (listed in dao.waiting(userId)) {
      if (listed.id in beingEdited) continue // open on the compose screen, which sends it (or lets it go) itself
      // Read again: the list is from when the flush began, and sending the letters before this one may have taken
      // minutes. Deleted since, or sent from the compose screen in an edited form, it must not go from here as well.
      val row = dao.get(listed.id)?.takeIf { it.state == OutboxEntity.STATE_WAITING } ?: continue
      when (val step = sendOne(row)) {
        Step.Sent -> sent++
        Step.Refused -> refused++
        Step.Dropped -> Unit
        Step.Unreadable -> unreadable += row.id
        // No point trying the next letter through the same broken connection, and order matters to a reader.
        is Step.Later -> { tryAgain = step.timeMayMend; break }
      }
    }
    // Not counting letters open for editing, nor ones that could not be read this time: nothing is wrong with them
    // that trying again would mend, and a worker told to retry backs off for longer each time, with the run booked
    // at their release waiting behind it.
    FlushOutcome(sent, refused, dao.waiting(userId).count { it.id !in beingEdited && it.id !in unreadable }, _limitedUntil.value, tryAgain)
  }

  private sealed interface Step {
    data object Sent : Step
    data object Refused : Step
    data object Dropped : Step
    /** The blob would not open this time (the Keystore can fail in passing). Left as it is: the next run may read it. */
    data object Unreadable : Step
    /** Stopped the run. [timeMayMend]: worth a retry by the worker; otherwise it waits for a person. */
    data class Later(val timeMayMend: Boolean) : Step
  }

  private suspend fun forget(row: OutboxEntity, payload: OutboxPayload) { payload.attachments.forEach { File(it.path).delete() }; dao.delete(row.id) }

  private suspend fun sendOne(start: OutboxEntity): Step {
    var row = start.copy(attempts = start.attempts + 1)
    // Not deleted when it will not open: the Keystore can fail in passing, and a letter is not thrown away for that.
    var payload = unseal(row) ?: return Step.Unreadable
    dao.update(row)

    // 1. The letter itself. The key makes a repeat harmless; `messageId` makes it unnecessary.
    if (row.messageId == null) when (val r = letters.send(payload.toNewLetter())) {
      is ApiResult.Success -> { row = row.copy(messageId = r.value.id); dao.update(row) }
      // Sent earlier, and deleted since (by the writer, on another device): it must not be sent again, and there is nothing to say.
      is ApiResult.Failure -> if (r.error is AppError.Gone) { forget(row, payload); return Step.Dropped } else return when (val verdict = judge(r.error, payload)) {
        is Verdict.Later -> { payload = noteFault(row, payload, verdict); Step.Later(verdict.timeMayMend) }
        is Verdict.Refused -> refuse(row, payload, verdict.reason)
      }
    }

    // 2. Its files, each at most once: a file that went up is struck off before the next is tried.
    val messageId = checkNotNull(row.messageId)
    for (attachment in payload.attachments) {
      val staged = withContext(Dispatchers.IO) { unsealFile(attachment) }
      val result = if (staged == null) ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.outbox_file_unreadable, attachment.name)))) else letters.upload(messageId, staged, attachment.key)
      staged?.let(files::discard)
      when (result) {
        is ApiResult.Success -> {
          File(attachment.path).delete()
          payload = payload.copy(attachments = payload.attachments - attachment)
          row = row.copy(sealed = seal(payload)); dao.update(row)
        }
        is ApiResult.Failure -> return when (val verdict = judge(result.error, payload)) {
          is Verdict.Later -> { payload = noteFault(row, payload, verdict); Step.Later(verdict.timeMayMend) }
          is Verdict.Refused -> refuse(row, payload, strings.get(R.string.outbox_sent_but_file_refused, attachment.name, verdict.reason))
        }
      }
    }
    dao.delete(row.id)
    return Step.Sent
  }

  /** A fault is counted on the letter (see [OutboxPayload.faults]); anything else leaves it as it was. */
  private suspend fun noteFault(row: OutboxEntity, payload: OutboxPayload, verdict: Verdict.Later): OutboxPayload {
    if (!verdict.fault) return payload
    val counted = payload.copy(faults = payload.faults + 1)
    dao.update(row.copy(sealed = seal(counted)))
    return counted
  }

  private suspend fun refuse(row: OutboxEntity, payload: OutboxPayload, reason: String): Step {
    // Set aside with its faults forgotten: "try as it is" is a fresh start.
    dao.update(row.copy(state = OutboxEntity.STATE_REFUSED, sealed = seal(payload.copy(problem = reason, faults = 0))))
    return Step.Refused
  }

  private sealed interface Verdict {
    /**
     * Try again when things change. With idempotency keys it no longer matters whether the last attempt arrived.
     * [timeMayMend]: the worker should come back by itself; otherwise a person's doing is waited for.
     * [fault]: the server, or the connection, answered with a fault, which is counted (see [OutboxPayload.faults]).
     */
    data class Later(val timeMayMend: Boolean, val fault: Boolean = false) : Verdict
    /** The server understood and said no. Trying again unchanged would get the same answer. */
    data class Refused(val reason: String) : Verdict
  }

  private fun judge(error: AppError, payload: OutboxPayload): Verdict = when {
    error is AppError.Network -> Verdict.Later(timeMayMend = true)             // no answer, or a Wi-Fi login page: not the letter's fault
    // A 5xx, or an answer that could not be read: the server's fault, or a proxy's, and usually passing. Not for ever,
    // though: a letter that keeps meeting one would hold up every letter behind it, unseen, until the end of time.
    error is AppError.Server && error.status >= 500 || error is AppError.Unexpected ->
      if (payload.faults + 1 >= OutboxPayload.MAX_FAULTS) Verdict.Refused(error.message(strings) ?: strings.get(R.string.outbox_refused_no_reason)) else Verdict.Later(timeMayMend = true, fault = true)
    // Paced, not refused: a limited letter was not saved, and the same key makes the later try safe (API PR #128).
    error is AppError.RateLimited -> Verdict.Later(timeMayMend = false).also { waitOut(error.retryAfterSeconds) } // a run is booked for the end of the wait
    error is AppError.Unauthorized -> Verdict.Later(timeMayMend = false)       // signed out: it waits for the next sign-in, which books a run
    error is AppError.Conflict && error.isStillProcessing -> Verdict.Later(timeMayMend = true) // our own earlier attempt is still in flight
    error == codec.locked -> Verdict.Later(timeMayMend = false)                // waits for the password, which books a run
    // Anything else the server said, including a 4xx this app has no name for (413: too big, say): trying again unchanged would get the same answer.
    else -> Verdict.Refused(error.message(strings) ?: strings.get(R.string.outbox_refused_no_reason))
  }
}

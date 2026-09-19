package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.text.Strings
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
) {
  fun toNewLetter() = NewLetter(prisonerId, body, relayNote, relayChapter, asWriterId, fromPrisoner, groupRelaysFacility, idempotencyKey)
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

data class FlushOutcome(val sent: Int = 0, val refused: Int = 0, val stillWaiting: Int = 0)

/** Arranges for [OutboxRepository.flush] to run when there is a network, even if the app is closed by then. */
interface OutboxScheduler { fun schedule() }

interface OutboxRepository {
  /** The signed-in account's queued letters, oldest first. Empty when signed out. */
  fun items(): Flow<List<OutboxItem>>
  suspend fun queue(prisonerName: String, writingAs: String?, letter: NewLetter, attachments: List<StagedFile>): Long
  /** For editing: the letter, with its files decrypted back into staging. */
  suspend fun open(id: Long): Pair<OutboxPayload, List<StagedFile>>?
  suspend fun delete(id: Long)
  /** After [open] and a successful re-send: drops the row and its encrypted files, which the new send has superseded. */
  suspend fun forget(id: Long) = delete(id)
  /** Puts a refused letter back in line, unchanged (the reason may have been temporary: a suspended group, say). */
  suspend fun retry(id: Long)
  /** Sends what can be sent now. Safe to call at any time and from anywhere; runs one at a time. */
  suspend fun flush(): FlushOutcome
  suspend fun hasWaiting(): Boolean
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
    val payload = OutboxPayload(letter.prisonerId, prisonerName, writingAs, letter.body, letter.relayNote, letter.relayChapter, letter.asWriterId, letter.fromPrisoner, letter.groupRelaysFacility, stored, idempotencyKey = letter.idempotencyKey ?: UUID.randomUUID().toString())
    return dao.insert(OutboxEntity(userId = userId, sealed = seal(payload), queuedAt = System.currentTimeMillis())).also { scheduler.schedule() }
  }

  override suspend fun open(id: Long): Pair<OutboxPayload, List<StagedFile>>? {
    val row = dao.get(id)?.takeIf { it.userId == myId } ?: return null
    val payload = unseal(row) ?: return null
    return payload to withContext(Dispatchers.IO) { payload.attachments.mapNotNull(::unsealFile) }
  }

  private fun unsealFile(a: OutboxAttachment): StagedFile? = runCatching {
    val plain = files.newStagingFile(a.name).apply { writeBytes(cipher.decrypt(File(a.path).readBytes())) }
    StagedFile(plain, a.name, a.mimeType, a.size)
  }.getOrNull()

  override suspend fun delete(id: Long) {
    val row = dao.get(id)?.takeIf { it.userId == myId } ?: return
    unseal(row)?.attachments?.forEach { File(it.path).delete() }
    dao.delete(id)
  }

  override suspend fun retry(id: Long) {
    val row = dao.get(id)?.takeIf { it.userId == myId } ?: return
    val payload = unseal(row) ?: return
    dao.update(row.copy(state = OutboxEntity.STATE_WAITING, sealed = seal(payload.copy(problem = null))))
    scheduler.schedule()
  }

  override suspend fun flush(): FlushOutcome = flushing.withLock {
    val userId = myId ?: return FlushOutcome(stillWaiting = dao.countWaiting())
    var sent = 0; var refused = 0
    for (row in dao.waiting(userId)) {
      when (sendOne(row)) {
        Step.SENT -> sent++
        Step.REFUSED -> refused++
        Step.DROPPED -> Unit
        // No point trying the next letter through the same broken connection, and order matters to a reader.
        Step.LATER -> break
      }
    }
    FlushOutcome(sent, refused, dao.waiting(userId).size)
  }

  private enum class Step { SENT, REFUSED, LATER, DROPPED }

  private suspend fun forget(row: OutboxEntity, payload: OutboxPayload) { payload.attachments.forEach { File(it.path).delete() }; dao.delete(row.id) }

  private suspend fun sendOne(start: OutboxEntity): Step {
    var row = start.copy(attempts = start.attempts + 1)
    var payload = unseal(row) ?: run { dao.delete(row.id); return Step.REFUSED } // the Keystore key is gone; nothing can read this any more
    dao.update(row)

    // 1. The letter itself. The key makes a repeat harmless; `messageId` makes it unnecessary.
    if (row.messageId == null) when (val r = letters.send(payload.toNewLetter())) {
      is ApiResult.Success -> { row = row.copy(messageId = r.value.id); dao.update(row) }
      // Sent earlier, and deleted since (by the writer, on another device): it must not be sent again, and there is nothing to say.
      is ApiResult.Failure -> if (r.error is AppError.Gone) { forget(row, payload); return Step.DROPPED } else return when (val verdict = judge(r.error)) {
        Verdict.Later -> Step.LATER
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
        is ApiResult.Failure -> return when (val verdict = judge(result.error)) {
          Verdict.Later -> Step.LATER
          is Verdict.Refused -> refuse(row, payload, strings.get(R.string.outbox_sent_but_file_refused, attachment.name, verdict.reason))
        }
      }
    }
    dao.delete(row.id)
    return Step.SENT
  }

  private suspend fun refuse(row: OutboxEntity, payload: OutboxPayload, reason: String): Step {
    dao.update(row.copy(state = OutboxEntity.STATE_REFUSED, sealed = seal(payload.copy(problem = reason))))
    return Step.REFUSED
  }

  private sealed interface Verdict {
    /** Try again when things change. With idempotency keys it no longer matters whether the last attempt arrived. */
    data object Later : Verdict
    /** The server understood and said no. Trying again unchanged would get the same answer. */
    data class Refused(val reason: String) : Verdict
  }

  private fun judge(error: AppError): Verdict = when {
    error is AppError.Network || error is AppError.Server || error is AppError.Unexpected -> Verdict.Later // no answer, a 5xx, or a Wi-Fi login page
    error is AppError.RateLimited -> Verdict.Later
    error is AppError.Unauthorized -> Verdict.Later                             // signed out: it waits for the next sign-in
    error is AppError.Conflict && error.isStillProcessing -> Verdict.Later      // our own earlier attempt is still in flight
    error == codec.locked -> Verdict.Later                                      // waits for the password
    else -> Verdict.Refused(error.userMessage ?: strings.get(R.string.outbox_refused_no_reason))
  }
}

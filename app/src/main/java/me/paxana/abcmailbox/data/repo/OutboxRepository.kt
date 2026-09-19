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
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.db.OutboxDao
import me.paxana.abcmailbox.data.db.OutboxEntity
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OutboxAttachment(val path: String, val name: String, val mimeType: String, val size: Long)

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
) {
  fun toNewLetter() = NewLetter(prisonerId, body, relayNote, relayChapter, asWriterId, fromPrisoner, groupRelaysFacility)
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
 * So the letter and its files are separate steps, each recorded the moment it succeeds; and when
 * an attempt ends without an answer (the request may or may not have arrived), the next attempt
 * first looks on the server for the letter before posting it again. The API has no idempotency
 * key, so that look is a comparison of writer, time and text.
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
  private val lettersApi: LettersApi,
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
    val payload = OutboxPayload(letter.prisonerId, prisonerName, writingAs, letter.body, letter.relayNote, letter.relayChapter, letter.asWriterId, letter.fromPrisoner, letter.groupRelaysFacility, stored)
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
        // No point trying the next letter through the same broken connection, and order matters to a reader.
        Step.LATER -> break
      }
    }
    FlushOutcome(sent, refused, dao.waiting(userId).size)
  }

  private enum class Step { SENT, REFUSED, LATER }

  private suspend fun sendOne(start: OutboxEntity): Step {
    var row = start.copy(attempts = start.attempts + 1)
    var payload = unseal(row) ?: run { dao.delete(row.id); return Step.REFUSED } // the Keystore key is gone; nothing can read this any more
    dao.update(row)

    // 1. The letter itself, at most once.
    if (row.messageId == null) {
      if (row.outcomeUnknown) {
        when (val found = alreadyOnServer(payload, row)) {
          is Lookup.Found -> { row = row.copy(messageId = found.id, outcomeUnknown = false); dao.update(row) }
          Lookup.NotThere -> { row = row.copy(outcomeUnknown = false); dao.update(row) }
          Lookup.CouldNotLook -> return Step.LATER
        }
      }
      if (row.messageId == null) when (val r = letters.send(payload.toNewLetter())) {
        is ApiResult.Success -> { row = row.copy(messageId = r.value.id, outcomeUnknown = false); dao.update(row) }
        is ApiResult.Failure -> return when (val verdict = judge(r.error)) {
          is Verdict.Later -> { dao.update(row.copy(outcomeUnknown = verdict.mayHaveArrived)); Step.LATER }
          is Verdict.Refused -> refuse(row, payload, verdict.reason)
        }
      }
    }

    // 2. Its files, each at most once: a file that went up is struck off before the next is tried.
    val messageId = checkNotNull(row.messageId)
    for (attachment in payload.attachments) {
      val staged = withContext(Dispatchers.IO) { unsealFile(attachment) }
      val result = if (staged == null) ApiResult.Failure(AppError.Validation(listOf(strings.get(R.string.outbox_file_unreadable, attachment.name)))) else letters.upload(messageId, staged)
      staged?.let(files::discard)
      when (result) {
        is ApiResult.Success -> {
          File(attachment.path).delete()
          payload = payload.copy(attachments = payload.attachments - attachment)
          row = row.copy(sealed = seal(payload)); dao.update(row)
        }
        is ApiResult.Failure -> return when (val verdict = judge(result.error)) {
          is Verdict.Later -> Step.LATER
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
    /** Try again when things change. [mayHaveArrived]: the request may have reached the server before the failure. */
    data class Later(val mayHaveArrived: Boolean) : Verdict
    /** The server understood and said no. Trying again unchanged would get the same answer. */
    data class Refused(val reason: String) : Verdict
  }

  private fun judge(error: AppError): Verdict = when (error) {
    // Never left the phone: no DNS, or nothing listening. Anything else (a timeout, a reset) may have arrived.
    is AppError.Network -> Verdict.Later(mayHaveArrived = error.cause !is UnknownHostException && error.cause !is ConnectException)
    is AppError.Server -> Verdict.Later(mayHaveArrived = true)       // a 500 can come after the row was written
    is AppError.Unexpected -> Verdict.Later(mayHaveArrived = true)   // a reply we could not read, e.g. a Wi-Fi login page
    is AppError.RateLimited -> Verdict.Later(mayHaveArrived = false)
    is AppError.Unauthorized -> Verdict.Later(mayHaveArrived = false) // signed out: it waits for the next sign-in
    else -> if (error == codec.locked) Verdict.Later(mayHaveArrived = false) // waits for the password
    else Verdict.Refused(error.userMessage ?: strings.get(R.string.outbox_refused_no_reason))
  }

  private sealed interface Lookup { data class Found(val id: Int) : Lookup; data object NotThere : Lookup; data object CouldNotLook : Lookup }

  /** Is there already a letter from this writer to this prisoner, since this one was queued, that says the same thing? */
  private suspend fun alreadyOnServer(payload: OutboxPayload, row: OutboxEntity): Lookup {
    codec.ready()
    val list = when (val r = apiCall(json) { lettersApi.messagesTo(payload.prisonerId) }) {
      is ApiResult.Failure -> return Lookup.CouldNotLook
      is ApiResult.Success -> r.value.data.orEmpty()
    }
    val since = Instant.ofEpochMilli(row.queuedAt).minusSeconds(CLOCK_SKEW_SECONDS)
    // A group's anonymous letter carries the anonymous account's id, which this phone does not know; the text and time decide.
    val writer = payload.asWriterId ?: row.userId.takeIf { !payload.fromPrisoner && payload.asWriterId == null && !isStaff }
    val match = list.firstOrNull { dto ->
      (dto.sender == "prisoner") == payload.fromPrisoner &&
        (writer == null || dto.user == writer) &&
        (dto.createdAt.toInstantOrNull()?.isAfter(since) ?: false) &&
        codec.incoming(dto).let { !it.locked && it.body == payload.body }
    }
    return match?.let { Lookup.Found(it.id) } ?: Lookup.NotThere
  }

  private val isStaff: Boolean get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.isStaff == true

  private companion object {
    /** The phone's clock and the server's are not the same clock. */
    const val CLOCK_SKEW_SECONDS = 600L
  }
}

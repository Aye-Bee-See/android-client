package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ReferenceLookup
import me.paxana.abcmailbox.domain.ReferencedLetter
import me.paxana.abcmailbox.domain.ReferencedPrisoner
import me.paxana.abcmailbox.domain.ReferencedWriter
import me.paxana.abcmailbox.domain.ReplyReference
import me.paxana.abcmailbox.domain.WriterMatch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filing a reply that came in the post (API PR #120): by the reference the prisoner copied from the letter's
 * footer, or, when there is none, by any name the writer has used.
 */
interface ReferenceRepository {
  /** The letter behind a number, and where its reply belongs. A wrong check digit is a 400 `checksum`; not this group's, a 404 `unknown`. */
  suspend fun lookup(number: String): ApiResult<ReferenceLookup>

  /** The writers whose letters this group mailed, by current or former pen name; at least two characters. */
  suspend fun writers(name: String): ApiResult<List<WriterMatch>>
}

@Singleton
class DefaultReferenceRepository @Inject constructor(private val api: LettersApi, private val json: Json) : ReferenceRepository {

  override suspend fun lookup(number: String): ApiResult<ReferenceLookup> =
    apiCall(json) { api.reference(ReplyReference.normalise(number)) }.map { env ->
      val d = checkNotNull(env.data) { "reference response had no data" }
      ReferenceLookup(
        reference = ReplyReference.pretty(d.reference),
        letter = d.letter?.let { ReferencedLetter(it.id, it.chat, LetterStatus.fromKey(it.status), it.paper, it.createdAt.toInstant()) },
        mailedAt = d.mailedAt.toInstant(),
        chatId = d.chat ?: d.letter?.chat,
        writer = ReferencedWriter(d.writer.id, d.writer.penName, d.writer.name, d.writer.anonymous),
        prisoner = d.prisoner?.let { ReferencedPrisoner(it.id, it.chosenName?.takeIf { n -> n.isNotBlank() } ?: it.birthName.orEmpty()) },
        careOfName = d.careOf?.name,
      )
    }

  override suspend fun writers(name: String): ApiResult<List<WriterMatch>> =
    apiCall(json) { api.writersByName(name.trim()) }.map { env ->
      env.data.orEmpty().map { w ->
        WriterMatch(w.id, w.penName?.takeIf { it.isNotBlank() } ?: w.name.orEmpty(), w.anonymous, w.matched?.name, w.matched?.current ?: true)
      }
    }

  private fun String?.toInstant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

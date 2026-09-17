package me.paxana.abcmailbox.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.IdBody
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.api.SendMessageRequest
import me.paxana.abcmailbox.data.api.UpdateMessageRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.api.toPage
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.Thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the compose screen sends. `relayChapter` null lets the server resolve it. */
data class NewLetter(
  val prisonerId: Int,
  val body: String,
  val relayNote: String?,
  val relayChapter: Int?,
  /** Group accounts: the managed writer this letter is from; null means the group's anonymous writer (or, for a writer, themselves). */
  val asWriterId: Int? = null,
  /** Group accounts: this is a prisoner's reply being recorded on `asWriterId`'s thread. */
  val fromPrisoner: Boolean = false,
  /** Group accounts, end-to-end: the group is a relay group of this facility, so the server lets it hold an envelope. */
  val groupRelaysFacility: Boolean = false,
)

data class LetterEdit(val messageId: Int, val body: String, val relayNote: String?, val relayChapter: Int?)

interface LettersRepository {
  fun threads(): Flow<PagingData<Thread>>
  suspend fun thread(chatId: Int): ApiResult<Thread>
  suspend fun threadForPrisoner(prisonerId: Int): ApiResult<Thread?>
  suspend fun letter(messageId: Int): ApiResult<Letter>
  suspend fun send(letter: NewLetter): ApiResult<Letter>
  suspend fun edit(edit: LetterEdit): ApiResult<Unit>
  suspend fun delete(messageId: Int): ApiResult<Unit>
  suspend fun upload(messageId: Int, staged: StagedFile): ApiResult<Attachment>
  suspend fun deleteAttachment(attachmentId: Int): ApiResult<Unit>
  /** Downloads to the cache and returns the file; a second call for the same attachment is instant. */
  suspend fun download(attachment: Attachment): ApiResult<File>
  suspend fun retentionDays(): ApiResult<Int?>
}

@Singleton
class DefaultLettersRepository @Inject constructor(
  private val api: LettersApi,
  private val json: Json,
  private val files: LocalFilesContract,
  private val codec: LetterCodec,
) : LettersRepository {

  private fun me.paxana.abcmailbox.data.api.ChatDto.decoded() = toDomain(letter = codec::incoming, preview = codec::preview)

  override fun threads(): Flow<PagingData<Thread>> = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
    PagePagingSource { page, size ->
      codec.ready()
      apiCall(json) { api.chats(page = page, pageSize = size) }.map { env -> env.toPage().map { it.decoded() } }
    }
  }.flow

  override suspend fun thread(chatId: Int): ApiResult<Thread> {
    codec.ready()
    return apiCall(json) { api.chat(chatId) }.map { checkNotNull(it.data).decoded() }
  }

  override suspend fun threadForPrisoner(prisonerId: Int): ApiResult<Thread?> {
    codec.ready()
    return when (val r = apiCall(json) { api.chatByPrisoner(prisonerId) }) {
      is ApiResult.Success -> ApiResult.Success(r.value.data?.decoded())
      is ApiResult.Failure -> if (r.error is AppError.NotFound) ApiResult.Success(null) else r
    }
  }

  override suspend fun letter(messageId: Int): ApiResult<Letter> {
    codec.ready()
    return apiCall(json) { api.message(messageId) }.map { codec.incoming(checkNotNull(it.data)) }
  }

  override suspend fun send(letter: NewLetter): ApiResult<Letter> {
    // A 409 here means a group rotated its key between our lookup and the send: encode again
    // (which fetches the new public key and version) and retry once.
    repeat(2) { attempt ->
      val request = when (val encoded = codec.outgoing(letter)) {
        is ApiResult.Failure -> return encoded
        is ApiResult.Success -> encoded.value.first
      }
      when (val r = apiCall(json) { api.send(request) }) {
        is ApiResult.Success -> return ApiResult.Success(codec.incoming(checkNotNull(r.value.data)))
        is ApiResult.Failure -> if (r.error !is AppError.Conflict || attempt == 1) return r else codec.refreshKeys()
      }
    }
    error("unreachable")
  }

  override suspend fun edit(edit: LetterEdit): ApiResult<Unit> {
    codec.ready()
    val existing = when (val r = apiCall(json) { api.message(edit.messageId) }) {
      is ApiResult.Failure -> return r
      is ApiResult.Success -> checkNotNull(r.value.data)
    }
    val request = when (val encoded = codec.edit(edit, existing)) {
      is ApiResult.Failure -> return encoded
      is ApiResult.Success -> encoded.value
    }
    return apiCall(json) { api.update(request) }.map { }
  }

  override suspend fun delete(messageId: Int): ApiResult<Unit> = apiCall(json) { api.delete(IdBody(messageId)) }.map { }

  override suspend fun upload(messageId: Int, staged: StagedFile): ApiResult<Attachment> {
    codec.ready()
    val messageField = messageId.toString().toRequestBody("text/plain".toMediaType())
    if (!codec.isEndToEnd()) {
      return apiCall(json) {
        api.upload(messageField, MultipartBody.Part.createFormData("file", staged.name, staged.file.asRequestBody(staged.mimeType.toMediaType())))
      }.map { checkNotNull(it.data).toDomain() }
    }
    // End-to-end: the file is encrypted under the letter's content key before it leaves the phone.
    // The declared type still describes the plaintext; the server does not sniff ciphertext.
    val key = when (val m = apiCall(json) { api.message(messageId) }) {
      is ApiResult.Failure -> return m
      is ApiResult.Success -> codec.contentKey(checkNotNull(m.value.data)) ?: return ApiResult.Failure(LetterCodec.LOCKED)
    }
    return apiCall(json) {
      val (cipherBytes, nonce) = withContext(Dispatchers.Default) { codec.encryptFile(staged.file.readBytes(), key) }
      api.upload(
        messageField,
        MultipartBody.Part.createFormData("file", staged.name, cipherBytes.toRequestBody(staged.mimeType.toMediaType())),
        nonce.toRequestBody("text/plain".toMediaType()),
      )
    }.map { checkNotNull(it.data).toDomain() }
  }

  override suspend fun deleteAttachment(attachmentId: Int): ApiResult<Unit> =
    apiCall(json) { api.deleteAttachment(IdBody(attachmentId)) }.map { }

  override suspend fun download(attachment: Attachment): ApiResult<File> {
    codec.ready()
    val target = files.downloadTarget(attachment.id, attachment.name)
    if (target.exists() && target.length() == attachment.size) return ApiResult.Success(target)
    val nonce = attachment.nonce
    if (nonce == null) {
      return apiCall(json) {
        withContext(Dispatchers.IO) {
          api.download(attachment.id).use { body -> body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
          target
        }
      }
    }
    // End-to-end: what comes down is ciphertext; open it with the letter's content key.
    val key = when (val m = apiCall(json) { api.message(attachment.messageId) }) {
      is ApiResult.Failure -> return m
      is ApiResult.Success -> codec.contentKey(checkNotNull(m.value.data)) ?: return ApiResult.Failure(LetterCodec.LOCKED)
    }
    return apiCall(json) {
      withContext(Dispatchers.IO) {
        val cipherBytes = api.download(attachment.id).use { it.bytes() }
        target.writeBytes(codec.decryptFile(cipherBytes, nonce, key))
        target
      }
    }
  }

  override suspend fun retentionDays(): ApiResult<Int?> = apiCall(json) { api.retention() }.map { it.data?.effectiveDays }
}

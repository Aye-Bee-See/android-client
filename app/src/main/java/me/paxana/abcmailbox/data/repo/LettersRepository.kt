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
data class NewLetter(val prisonerId: Int, val body: String, val relayNote: String?, val relayChapter: Int?)

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
) : LettersRepository {

  override fun threads(): Flow<PagingData<Thread>> = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
    PagePagingSource { page, size ->
      apiCall(json) { api.chats(page = page, pageSize = size) }.map { env -> env.toPage().map { it.toDomain() } }
    }
  }.flow

  override suspend fun thread(chatId: Int): ApiResult<Thread> =
    apiCall(json) { api.chat(chatId) }.map { checkNotNull(it.data).toDomain() }

  override suspend fun threadForPrisoner(prisonerId: Int): ApiResult<Thread?> =
    when (val r = apiCall(json) { api.chatByPrisoner(prisonerId) }) {
      is ApiResult.Success -> ApiResult.Success(r.value.data?.toDomain())
      is ApiResult.Failure -> if (r.error is AppError.NotFound) ApiResult.Success(null) else r
    }

  override suspend fun letter(messageId: Int): ApiResult<Letter> =
    apiCall(json) { api.message(messageId) }.map { checkNotNull(it.data).toDomain() }

  override suspend fun send(letter: NewLetter): ApiResult<Letter> = apiCall(json) {
    api.send(
      SendMessageRequest(
        messageText = letter.body,
        prisoner = letter.prisonerId,
        relayChapter = letter.relayChapter,
        relayNote = letter.relayNote?.takeIf { it.isNotBlank() },
      )
    )
  }.map { checkNotNull(it.data).toDomain() }

  override suspend fun edit(edit: LetterEdit): ApiResult<Unit> = apiCall(json) {
    api.update(UpdateMessageRequest(id = edit.messageId, messageText = edit.body, relayNote = edit.relayNote, relayChapter = edit.relayChapter))
  }.map { }

  override suspend fun delete(messageId: Int): ApiResult<Unit> = apiCall(json) { api.delete(IdBody(messageId)) }.map { }

  override suspend fun upload(messageId: Int, staged: StagedFile): ApiResult<Attachment> = apiCall(json) {
    val part = MultipartBody.Part.createFormData("file", staged.name, staged.file.asRequestBody(staged.mimeType.toMediaType()))
    api.upload(messageId.toString().toRequestBody("text/plain".toMediaType()), part)
  }.map { checkNotNull(it.data).toDomain() }

  override suspend fun deleteAttachment(attachmentId: Int): ApiResult<Unit> =
    apiCall(json) { api.deleteAttachment(IdBody(attachmentId)) }.map { }

  override suspend fun download(attachment: Attachment): ApiResult<File> {
    val target = files.downloadTarget(attachment.id, attachment.name)
    if (target.exists() && target.length() == attachment.size) return ApiResult.Success(target)
    return apiCall(json) {
      withContext(Dispatchers.IO) {
        api.download(attachment.id).use { body ->
          body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
      }
    }
  }

  override suspend fun retentionDays(): ApiResult<Int?> = apiCall(json) { api.retention() }.map { it.data?.effectiveDays }
}

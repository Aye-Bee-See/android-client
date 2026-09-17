package me.paxana.abcmailbox.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.AddWriterRequest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.StatusRequest
import me.paxana.abcmailbox.data.api.WriterDto
import me.paxana.abcmailbox.data.api.WriterRef
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.api.toPage
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.domain.IssuedToken
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.QueueItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface GroupRepository {
  /** Letters the group relays, in one status. Each comes with the prisoner, for addressing. */
  fun queue(groupId: Int, status: LetterStatus): Flow<PagingData<QueueItem>>
  suspend fun queueItem(messageId: Int): ApiResult<QueueItem>
  suspend fun setStatus(messageId: Int, status: LetterStatus): ApiResult<Letter>
  suspend fun writers(): ApiResult<List<ManagedWriter>>
  suspend fun addWriter(name: String, email: String?, note: String?): ApiResult<ManagedWriter>
  suspend fun issueToken(writerId: Int): ApiResult<IssuedToken>
  suspend fun revokeToken(writerId: Int): ApiResult<Unit>
}

@Singleton
class DefaultGroupRepository @Inject constructor(
  private val api: GroupApi,
  private val letters: LettersRepository,
  private val directory: DirectoryRepository,
  private val codec: LetterCodec,
  private val json: Json,
) : GroupRepository {

  // Queue rows name the prisoner by id only, so each distinct prisoner is fetched once and remembered.
  private val prisoners = ConcurrentHashMap<Int, Prisoner>()
  private suspend fun prisoner(id: Int): Prisoner? =
    prisoners[id] ?: (directory.prisoner(id) as? ApiResult.Success)?.value?.also { prisoners[id] = it }

  override fun queue(groupId: Int, status: LetterStatus): Flow<PagingData<QueueItem>> = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
    PagePagingSource { page, size ->
      when (val r = apiCall(json) { api.relayed(groupId, status.key, page, size) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> {
          val p = r.value.toPage()
          ApiResult.Success(me.paxana.abcmailbox.data.api.Page(p.items.map { dto -> QueueItem(codec.incoming(dto), prisoner(dto.prisoner)) }, p.total, p.page, p.pageSize))
        }
      }
    }
  }.flow

  override suspend fun queueItem(messageId: Int): ApiResult<QueueItem> = when (val r = letters.letter(messageId)) {
    is ApiResult.Failure -> r
    is ApiResult.Success -> ApiResult.Success(QueueItem(r.value, r.value.prisonerId?.let { prisoner(it) }))
  }

  override suspend fun setStatus(messageId: Int, status: LetterStatus): ApiResult<Letter> =
    apiCall(json) { api.setStatus(StatusRequest(messageId, status.key)) }.map { codec.incoming(checkNotNull(it.data)) }

  override suspend fun writers(): ApiResult<List<ManagedWriter>> =
    apiCall(json) { api.writers() }.map { env -> env.data.orEmpty().filter { it.anonymousForChapter == null }.map { it.toDomain() }.sortedBy { it.name.lowercase() } }

  override suspend fun addWriter(name: String, email: String?, note: String?): ApiResult<ManagedWriter> =
    apiCall(json) { api.addWriter(AddWriterRequest(name.trim(), email?.trim()?.ifBlank { null }, note?.trim()?.ifBlank { null })) }.map { checkNotNull(it.data).toDomain() }

  override suspend fun issueToken(writerId: Int): ApiResult<IssuedToken> = when (val r = apiCall(json) { api.issueToken(WriterRef(writerId)) }) {
    is ApiResult.Failure -> r
    is ApiResult.Success -> r.value.data?.token?.let { ApiResult.Success(IssuedToken(it, r.value.data?.expiresAt.toInstantOrNull())) }
      ?: ApiResult.Failure(AppError.Validation(listOf("This server is end-to-end encrypted, where the token is made on the device. That arrives in the next build.")))
  }

  override suspend fun revokeToken(writerId: Int): ApiResult<Unit> = apiCall(json) { api.revokeToken(WriterRef(writerId)) }.map { }
}

fun WriterDto.toDomain() = ManagedWriter(
  id = id,
  name = name?.takeIf { it.isNotBlank() } ?: username ?: "Writer $id",
  email = email?.takeIf { it.isNotBlank() && !it.endsWith("@managed.example") },
  note = managerNote?.takeIf { it.isNotBlank() },
  tokenExpiresAt = claimToken?.expiresAt.toInstantOrNull(),
)

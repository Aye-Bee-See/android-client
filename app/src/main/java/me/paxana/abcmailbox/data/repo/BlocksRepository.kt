package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.BanRecommendationDto
import me.paxana.abcmailbox.data.api.BlockRequest
import me.paxana.abcmailbox.data.api.BlockWriterDto
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.UnblockRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Who a block or a recommendation is about: their pen name first, as the group knows them from the letters. */
data class BlockedWriter(val id: Int, val penName: String?, val name: String?, val username: String? = null) {
  val shownName: String? get() = penName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() }?.let { "@$it" }
}

/** A writer this group will not mail letters for (API #171). The [reason] is the one the writer was told. */
data class WriterBlock(val writer: BlockedWriter, val reason: String, val blockedBy: String?, val blockedAt: Instant?)

/** A group's request that a superadmin block a writer everywhere (API #172), and what became of it. */
data class BanRecommendation(
  val id: Int, val writer: BlockedWriter?, val reason: String, val status: Status, val decisionNote: String?, val decidedAt: Instant?, val createdAt: Instant?,
) {
  enum class Status(val key: String) {
    PENDING("pending"), BANNED("banned"), DISMISSED("dismissed");
    companion object { fun fromKey(key: String?): Status = entries.firstOrNull { it.key == key } ?: PENDING }
  }
}

/**
 * A group's two answers to a writer misusing the system: its own block, which reaches its own letters only and which
 * the writer is told of with the reason, and a recommendation to the superadmins, which the writer is not told of.
 */
interface BlocksRepository {
  suspend fun blocks(): ApiResult<List<WriterBlock>>
  /** Answers how many of the writer's letters in the queue are now held. */
  suspend fun block(writerId: Int, reason: String): ApiResult<Int>
  /** Answers how many held letters went back into the queue. */
  suspend fun unblock(writerId: Int): ApiResult<Int>
  suspend fun recommendations(): ApiResult<List<BanRecommendation>>
  suspend fun recommendBan(writerId: Int, reason: String): ApiResult<Unit>

  companion object {
    /** The API's limits: the block's reason reaches the writer, the recommendation's only a superadmin. */
    const val BLOCK_REASON_MAX = 500
    const val RECOMMENDATION_REASON_MAX = 1000
  }
}

@Singleton
class DefaultBlocksRepository @Inject constructor(private val api: GroupApi, private val json: Json) : BlocksRepository {
  override suspend fun blocks(): ApiResult<List<WriterBlock>> = apiCall(json) { api.blocks() }.map { env ->
    env.data.orEmpty().mapNotNull { b ->
      val writer = b.writer?.toDomain() ?: return@mapNotNull null
      WriterBlock(writer, b.reason.orEmpty(), b.blockedBy?.let { it.name?.takeIf(String::isNotBlank) ?: it.username }, b.blockedAt.toInstantOrNull())
    }
  }

  override suspend fun block(writerId: Int, reason: String): ApiResult<Int> =
    apiCall(json) { api.block(BlockRequest(writerId, reason.trim().take(BlocksRepository.BLOCK_REASON_MAX))) }.map { it.data?.held ?: 0 }

  override suspend fun unblock(writerId: Int): ApiResult<Int> = apiCall(json) { api.unblock(UnblockRequest(writerId)) }.map { it.data?.released ?: 0 }

  override suspend fun recommendations(): ApiResult<List<BanRecommendation>> = apiCall(json) { api.banRecommendations() }.map { env -> env.data.orEmpty().map { it.toDomain() } }

  override suspend fun recommendBan(writerId: Int, reason: String): ApiResult<Unit> =
    apiCall(json) { api.recommendBan(BlockRequest(writerId, reason.trim().take(BlocksRepository.RECOMMENDATION_REASON_MAX))) }.map { }
}

private fun BlockWriterDto.toDomain() = BlockedWriter(id, penName, name, username)

internal fun BanRecommendationDto.toDomain() = BanRecommendation(
  id, writer?.toDomain(), reason.orEmpty(), BanRecommendation.Status.fromKey(status), decisionNote?.takeIf { it.isNotBlank() },
  decidedAt.toInstantOrNull(), createdAt.toInstantOrNull(),
)

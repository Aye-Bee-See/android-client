package me.paxana.abcmailbox.data.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.data.api.ApiEnvelope
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.ChapterDto
import me.paxana.abcmailbox.data.api.DirectorySyncApi
import me.paxana.abcmailbox.data.api.MailRuleVocabularyDto
import me.paxana.abcmailbox.data.api.Page
import me.paxana.abcmailbox.data.api.PrisonDto
import me.paxana.abcmailbox.data.api.PrisonerDto
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.db.CachedFacility
import me.paxana.abcmailbox.data.db.CachedGroup
import me.paxana.abcmailbox.data.db.CachedPrisoner
import me.paxana.abcmailbox.data.db.DirectoryCacheDao
import me.paxana.abcmailbox.data.db.DirectoryCounts
import me.paxana.abcmailbox.data.db.DirectoryMeta
import me.paxana.abcmailbox.data.repo.FacilityFilter
import me.paxana.abcmailbox.data.repo.GroupFilter
import me.paxana.abcmailbox.data.repo.PrisonerFilter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone holds for use without a connection. `savedAt` null means nothing has been downloaded yet. */
data class OfflineStatus(val savedAt: Instant?, val counts: DirectoryCounts) {
  val isEmpty: Boolean get() = savedAt == null
}

/**
 * A copy of the public directory on the phone, for a letter-writing night in a room with
 * no signal: everyone's address and mail rules, searchable, as of the last download.
 * Reads answer with the same DTOs the API client produces, so callers map them the same way.
 */
interface OfflineDirectory {
  val status: Flow<OfflineStatus>
  /** Downloads everything and swaps it in. Nothing changes on disk unless the whole download succeeded. */
  suspend fun download(): ApiResult<Unit>
  /** Downloads only if there is no copy or it is older than [maxAgeHours]. Failures are silent: this is housekeeping. */
  suspend fun downloadIfOlderThan(maxAgeHours: Long = 24)
  suspend fun savedAt(): Instant?
  suspend fun prisoners(filter: PrisonerFilter, page: Int, pageSize: Int): Page<PrisonerDto>
  suspend fun facilities(filter: FacilityFilter, page: Int, pageSize: Int): Page<PrisonDto>
  suspend fun groups(filter: GroupFilter, page: Int, pageSize: Int): Page<ChapterDto>
  suspend fun prisoner(id: Int): PrisonerDto?
  suspend fun facility(id: Int): PrisonDto?
  suspend fun group(id: Int): ChapterDto?
  suspend fun mailRules(): MailRuleVocabularyDto?
}

@Singleton
class RoomOfflineDirectory @Inject constructor(
  private val api: DirectorySyncApi,
  private val dao: DirectoryCacheDao,
  private val json: Json,
) : OfflineDirectory {

  private val downloading = Mutex()

  override val status: Flow<OfflineStatus> =
    dao.observeMeta(SAVED_AT).combine(dao.observeCounts()) { at, counts -> OfflineStatus(at?.let(::parseInstant), counts) }

  override suspend fun savedAt(): Instant? = dao.meta(SAVED_AT)?.let(::parseInstant)

  override suspend fun downloadIfOlderThan(maxAgeHours: Long) {
    val at = savedAt()
    if (at == null || at.isBefore(Instant.now().minusSeconds(maxAgeHours * 3600))) download()
  }

  override suspend fun download(): ApiResult<Unit> = downloading.withLock {
    val prisoners = when (val r = everyPage { api.prisoners(it) }) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    val facilities = when (val r = everyPage { api.prisons(it) }) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    val groups = when (val r = everyPage { api.chapters(it) }) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
    // The rule list is a convenience (facilities carry their own rules' wording), so its failure does not fail the download.
    val rules = (apiCall(json) { api.mailRules() } as? ApiResult.Success)?.value?.data

    dao.replaceAll(
      prisoners = prisoners.mapNotNull { row ->
        val p = row.decodeOrNull<PrisonerDto>() ?: return@mapNotNull null
        val names = listOfNotNull(p.chosenName, p.birthName).filter { it.isNotBlank() }
        CachedPrisoner(p.id, names.firstOrNull() ?: "", names.joinToString("\n").lowercase(), p.status, p.country, p.featured, p.prison, row.text("createdAt"), row.toString())
      },
      facilities = facilities.mapNotNull { row ->
        val f = row.decodeOrNull<PrisonDto>() ?: return@mapNotNull null
        val hasRelay = f.relayGroups.orEmpty().any { it.accountStatus == null || it.accountStatus == "active" }
        CachedFacility(f.id, f.prisonName, f.prisonName.lowercase(), f.country, f.routing, hasRelay, row.text("createdAt"), row.toString())
      },
      groups = groups.mapNotNull { row ->
        val g = row.decodeOrNull<ChapterDto>() ?: return@mapNotNull null
        CachedGroup(g.id, g.name, g.name.lowercase(), g.country, g.services.orEmpty().joinToString("|", "|", "|"), g.networkRole, row.text("createdAt"), row.toString())
      },
      meta = listOfNotNull(DirectoryMeta(SAVED_AT, Instant.now().toString()), rules?.let { DirectoryMeta(MAIL_RULES, it.toString()) }),
    )
    ApiResult.Success(Unit)
  }

  /** Walks a paginated list to its end. `total` comes with every page, so the loop knows when it has everything. */
  private suspend fun everyPage(fetch: suspend (page: Int) -> ApiEnvelope<List<JsonObject>>): ApiResult<List<JsonObject>> {
    val rows = mutableListOf<JsonObject>()
    var page = 1
    while (page <= MAX_PAGES) {
      val env = when (val r = apiCall(json) { fetch(page) }) { is ApiResult.Failure -> return r; is ApiResult.Success -> r.value }
      val batch = env.data.orEmpty()
      rows += batch
      if (batch.isEmpty() || rows.size >= (env.total ?: rows.size)) break
      page++
    }
    return ApiResult.Success(rows)
  }

  override suspend fun prisoners(filter: PrisonerFilter, page: Int, pageSize: Int): Page<PrisonerDto> {
    val q = filter.query.trim().lowercase()
    val rows = dao.prisoners(q, filter.status, filter.country, filter.featured, filter.facilityId, filter.sort, pageSize, (page - 1) * pageSize)
    return Page(rows.mapNotNull { it.decodeOrNull<PrisonerDto>() }, dao.countPrisoners(q, filter.status, filter.country, filter.featured, filter.facilityId), page, pageSize)
  }

  override suspend fun facilities(filter: FacilityFilter, page: Int, pageSize: Int): Page<PrisonDto> {
    val q = filter.query.trim().lowercase()
    val rows = dao.facilities(q, filter.country, filter.routing, filter.relay, filter.sort, pageSize, (page - 1) * pageSize)
    return Page(rows.mapNotNull { it.decodeOrNull<PrisonDto>() }, dao.countFacilities(q, filter.country, filter.routing, filter.relay), page, pageSize)
  }

  override suspend fun groups(filter: GroupFilter, page: Int, pageSize: Int): Page<ChapterDto> {
    val q = filter.query.trim().lowercase()
    val rows = dao.groups(q, filter.country, filter.service, filter.networkRole, filter.sort, pageSize, (page - 1) * pageSize)
    return Page(rows.mapNotNull { it.decodeOrNull<ChapterDto>() }, dao.countGroups(q, filter.country, filter.service, filter.networkRole), page, pageSize)
  }

  override suspend fun prisoner(id: Int): PrisonerDto? = dao.prisoner(id)?.decodeOrNull()
  override suspend fun facility(id: Int): PrisonDto? = dao.facility(id)?.decodeOrNull()
  override suspend fun group(id: Int): ChapterDto? = dao.group(id)?.decodeOrNull()
  override suspend fun mailRules(): MailRuleVocabularyDto? = dao.meta(MAIL_RULES)?.decodeOrNull()

  // One record the app cannot read must not cost the whole directory, so a bad row is skipped, not thrown.
  private inline fun <reified T> JsonObject.decodeOrNull(): T? = runCatching { json.decodeFromJsonElement<T>(this) }.getOrNull()
  private inline fun <reified T> String.decodeOrNull(): T? = runCatching { json.decodeFromString<T>(this) }.getOrNull()
  private fun JsonObject.text(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
  private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

  private companion object {
    const val SAVED_AT = "savedAt"
    const val MAIL_RULES = "mailRules"
    const val MAX_PAGES = 200 // 20,000 records: far beyond any real directory, and a stop for a server that never says "done"
  }
}

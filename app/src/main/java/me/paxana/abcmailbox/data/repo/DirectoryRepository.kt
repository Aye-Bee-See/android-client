package me.paxana.abcmailbox.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.DirectoryApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.api.toPage
import me.paxana.abcmailbox.data.offline.OfflineDirectory
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.MailRule
import me.paxana.abcmailbox.domain.MailRuleCatalog
import me.paxana.abcmailbox.domain.Prisoner
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the list screens can ask for; `null` means "no filter". */
data class PrisonerFilter(
  val query: String = "",
  val status: String? = null,
  val country: String? = null,
  val featured: Boolean? = null,
  val facilityId: Int? = null,
  val sort: String = "name",
)

data class FacilityFilter(
  val query: String = "",
  val country: String? = null,
  val routing: String? = null,
  val relay: Boolean? = null,
  val sort: String = "name",
)

data class GroupFilter(
  val query: String = "",
  val country: String? = null,
  val service: String? = null,
  val networkRole: String? = null,
  val sort: String = "name",
)

/** Where the directory on screen came from. Screens say so when it is the saved copy. */
sealed interface DirectorySource {
  data object Live : DirectorySource
  data class Saved(val at: Instant) : DirectorySource
}

interface DirectoryRepository {
  /** Flips to [DirectorySource.Saved] when a read had to be answered from the phone, and back on the next read that reaches the server. */
  val source: StateFlow<DirectorySource> get() = MutableStateFlow(DirectorySource.Live)
  fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>>
  fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>>
  fun groups(filter: GroupFilter): Flow<PagingData<Group>>
  suspend fun featuredPrisoners(limit: Int = 6): ApiResult<List<Prisoner>>
  suspend fun prisoner(id: Int): ApiResult<Prisoner>
  suspend fun facility(id: Int): ApiResult<Facility>
  suspend fun group(id: Int): ApiResult<Group>
}

@Singleton
class DefaultDirectoryRepository @Inject constructor(
  private val api: DirectoryApi,
  private val json: Json,
  private val offline: OfflineDirectory,
) : DirectoryRepository {

  @Volatile private var liveCatalog: MailRuleCatalog? = null

  /**
   * The live master list, fetched once per process; the compiled-in copy if the
   * server cannot be reached. Admins can change the list at any time (API PR #93),
   * so it is only the fallback: each facility read carries the wording of its own
   * rules (`mail_rule_details`), which the mapper prefers.
   */
  private suspend fun catalog(): MailRuleCatalog {
    liveCatalog?.let { return it }
    val compiled = MailRuleCatalog.Compiled
    val live = (apiCall(json) { api.mailRuleVocabulary() } as? ApiResult.Success)?.value?.data
    // Without a connection: the list saved with the offline copy, then the one compiled into the app.
    // Only a live list is kept for the rest of the process, so a connection that comes back is used.
    val fetched = live ?: offline.mailRules() ?: return compiled
    return MailRuleCatalog(
      categories = fetched.categories,
      rules = fetched.rules.map { r -> MailRule(r.tag, r.category, r.label ?: compiled.resolve(r.tag).label, r.description) },
    ).also { if (live != null) liveCatalog = it }
  }

  private val config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 5)

  private val _source = MutableStateFlow<DirectorySource>(DirectorySource.Live)
  override val source: StateFlow<DirectorySource> = _source.asStateFlow()

  /**
   * Network first. Only a failure to reach our server falls back to the saved copy: a 404 or a 403
   * is the server's answer and stands. With no saved copy, or nothing saved for this read, the
   * network error is what the screen shows.
   */
  private suspend fun <T> orSaved(live: ApiResult<T>, saved: suspend () -> T?): ApiResult<T> {
    if (live is ApiResult.Success) { _source.value = DirectorySource.Live; return live }
    if (!(live as ApiResult.Failure).error.meansNotReachingOurServer()) return live
    val at = offline.savedAt() ?: return live
    val value = saved() ?: return live
    _source.value = DirectorySource.Saved(at)
    return ApiResult.Success(value)
  }

  /**
   * No connection, or a reply that is not our API's at all. The second is what captive-portal Wi-Fi
   * looks like (a community centre's "accept the terms" page answers every request with a 200 and
   * HTML), and it is as good as offline.
   */
  private fun AppError.meansNotReachingOurServer(): Boolean =
    this is AppError.Network || (this is AppError.Unexpected && cause is SerializationException)

  override fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>> = Pager(config) {
    PagePagingSource { page, size ->
      val live = apiCall(json) {
        api.prisoners(
          q = filter.query.ifBlank { null }, prison = filter.facilityId, status = filter.status,
          country = filter.country, featured = filter.featured, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { it.toPage() }
      orSaved(live) { offline.prisoners(filter, page, size) }.map { p -> val c = catalog(); p.map { it.toDomain(c) } }
    }
  }.flow

  override fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>> = Pager(config) {
    PagePagingSource { page, size ->
      val live = apiCall(json) {
        api.prisons(
          q = filter.query.ifBlank { null }, country = filter.country, routing = filter.routing,
          relay = filter.relay, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { it.toPage() }
      orSaved(live) { offline.facilities(filter, page, size) }.map { p -> val c = catalog(); p.map { it.toDomain(c) } }
    }
  }.flow

  override fun groups(filter: GroupFilter): Flow<PagingData<Group>> = Pager(config) {
    PagePagingSource { page, size ->
      val live = apiCall(json) {
        api.chapters(
          q = filter.query.ifBlank { null }, country = filter.country, service = filter.service,
          networkRole = filter.networkRole, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { it.toPage() }
      orSaved(live) { offline.groups(filter, page, size) }.map { p -> val c = catalog(); p.map { it.toDomain(c) } }
    }
  }.flow

  override suspend fun featuredPrisoners(limit: Int): ApiResult<List<Prisoner>> {
    val live = apiCall(json) { api.prisoners(featured = true, sort = "newest", pageSize = limit) }.map { it.data.orEmpty() }
    return orSaved(live) { offline.prisoners(PrisonerFilter(featured = true, sort = "newest"), 1, limit).items }
      .map { rows -> val c = catalog(); rows.map { it.toDomain(c) } }
  }

  override suspend fun prisoner(id: Int): ApiResult<Prisoner> =
    orSaved(apiCall(json) { api.prisoner(id) }.map { checkNotNull(it.data) }) { offline.prisoner(id) }.map { it.toDomain(catalog()) }

  override suspend fun facility(id: Int): ApiResult<Facility> =
    orSaved(apiCall(json) { api.prison(id) }.map { checkNotNull(it.data) }) { offline.facility(id) }.map { it.toDomain(catalog()) }

  override suspend fun group(id: Int): ApiResult<Group> =
    orSaved(apiCall(json) { api.chapter(id) }.map { checkNotNull(it.data) }) { offline.group(id) }.map { it.toDomain(catalog()) }

  private companion object {
    // page_size is capped at 100 by the API; 20 keeps first paint quick on a phone.
    const val PAGE_SIZE = 20
  }
}

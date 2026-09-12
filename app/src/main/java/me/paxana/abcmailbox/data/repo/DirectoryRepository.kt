package me.paxana.abcmailbox.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.DirectoryApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.api.toPage
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.Prisoner
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

interface DirectoryRepository {
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
) : DirectoryRepository {

  private val config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 5)

  override fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>> = Pager(config) {
    PagePagingSource { page, size ->
      apiCall(json) {
        api.prisoners(
          q = filter.query.ifBlank { null }, prison = filter.facilityId, status = filter.status,
          country = filter.country, featured = filter.featured, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { env -> env.toPage().map { it.toDomain() } }
    }
  }.flow

  override fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>> = Pager(config) {
    PagePagingSource { page, size ->
      apiCall(json) {
        api.prisons(
          q = filter.query.ifBlank { null }, country = filter.country, routing = filter.routing,
          relay = filter.relay, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { env -> env.toPage().map { it.toDomain() } }
    }
  }.flow

  override fun groups(filter: GroupFilter): Flow<PagingData<Group>> = Pager(config) {
    PagePagingSource { page, size ->
      apiCall(json) {
        api.chapters(
          q = filter.query.ifBlank { null }, country = filter.country, service = filter.service,
          networkRole = filter.networkRole, sort = filter.sort, page = page, pageSize = size,
        )
      }.map { env -> env.toPage().map { it.toDomain() } }
    }
  }.flow

  override suspend fun featuredPrisoners(limit: Int): ApiResult<List<Prisoner>> =
    apiCall(json) { api.prisoners(featured = true, sort = "newest", pageSize = limit) }
      .map { it.data.orEmpty().map { dto -> dto.toDomain() } }

  override suspend fun prisoner(id: Int): ApiResult<Prisoner> =
    apiCall(json) { api.prisoner(id) }.map { checkNotNull(it.data).toDomain() }

  override suspend fun facility(id: Int): ApiResult<Facility> =
    apiCall(json) { api.prison(id) }.map { checkNotNull(it.data).toDomain() }

  override suspend fun group(id: Int): ApiResult<Group> =
    apiCall(json) { api.chapter(id) }.map { checkNotNull(it.data).toDomain() }

  private companion object {
    // page_size is capped at 100 by the API; 20 keeps first paint quick on a phone.
    const val PAGE_SIZE = 20
  }
}

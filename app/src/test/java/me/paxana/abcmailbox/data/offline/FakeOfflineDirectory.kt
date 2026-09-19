package me.paxana.abcmailbox.data.offline

import kotlinx.coroutines.flow.MutableStateFlow
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.ChapterDto
import me.paxana.abcmailbox.data.api.MailRuleVocabularyDto
import me.paxana.abcmailbox.data.api.Page
import me.paxana.abcmailbox.data.api.PrisonDto
import me.paxana.abcmailbox.data.api.PrisonerDto
import me.paxana.abcmailbox.data.db.DirectoryCounts
import me.paxana.abcmailbox.data.repo.FacilityFilter
import me.paxana.abcmailbox.data.repo.GroupFilter
import me.paxana.abcmailbox.data.repo.PrisonerFilter
import java.time.Instant

/** A saved copy a test fills by hand. Empty, with no date, it behaves like a phone that never downloaded anything. */
class FakeOfflineDirectory(var at: Instant? = null) : OfflineDirectory {
  val savedPrisoners = mutableListOf<PrisonerDto>(); val savedFacilities = mutableListOf<PrisonDto>(); val savedGroups = mutableListOf<ChapterDto>()
  var rules: MailRuleVocabularyDto? = null
  var downloads = 0; val asked = mutableListOf<Any>()
  override val status = MutableStateFlow(OfflineStatus(at, DirectoryCounts(0, 0, 0)))
  override suspend fun download(): ApiResult<Unit> { downloads++; return ApiResult.Success(Unit) }
  override suspend fun downloadIfOlderThan(maxAgeHours: Long) { if (at == null) download() }
  override suspend fun savedAt() = at
  override suspend fun prisoners(filter: PrisonerFilter, page: Int, pageSize: Int): Page<PrisonerDto> { asked += filter; return Page(savedPrisoners.filter { filter.featured == null || it.featured == filter.featured }, savedPrisoners.size, page, pageSize) }
  override suspend fun facilities(filter: FacilityFilter, page: Int, pageSize: Int): Page<PrisonDto> { asked += filter; return Page(savedFacilities, savedFacilities.size, page, pageSize) }
  override suspend fun groups(filter: GroupFilter, page: Int, pageSize: Int): Page<ChapterDto> { asked += filter; return Page(savedGroups, savedGroups.size, page, pageSize) }
  override suspend fun prisoner(id: Int) = savedPrisoners.firstOrNull { it.id == id }
  override suspend fun facility(id: Int) = savedFacilities.firstOrNull { it.id == id }
  override suspend fun group(id: Int) = savedGroups.firstOrNull { it.id == id }
  override suspend fun mailRules() = rules
}

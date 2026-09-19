package me.paxana.abcmailbox.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline directory must answer a search the way the server would, or a volunteer gets
 * different results in the basement than at home. These run on a device because they need SQLite.
 */
@RunWith(AndroidJUnit4::class)
class DirectoryCacheDaoTest {
  private lateinit var db: AppDatabase
  private lateinit var dao: DirectoryCacheDao

  private fun prisoner(id: Int, chosen: String?, birth: String?, status: String = "incarcerated", country: String = "United States", featured: Boolean = false, facility: Int = 1, created: String = "2026-01-0${id}T00:00:00.000Z") =
    CachedPrisoner(id, chosen ?: birth ?: "", listOfNotNull(chosen, birth).joinToString("\n").lowercase(), status, country, featured, facility, created, """{"id":$id}""")
  private fun group(id: Int, name: String, services: List<String>, role: String) =
    CachedGroup(id, name, name.lowercase(), "United States", services.joinToString("|", "|", "|"), role, null, """{"id":$id}""")

  @Before
  fun setUp() = runTest {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    dao = db.directoryCache()
    dao.replaceAll(
      prisoners = listOf(
        prisoner(1, "Jane Smith", "John Smith", featured = true),
        prisoner(2, null, "alex johnson", status = "pretrial", facility = 2),
        prisoner(3, "Zed", "Michael Smithson", country = "Belarus", facility = 2),
      ),
      facilities = listOf(
        CachedFacility(1, "Test Prison", "test prison", "United States", "direct", true, "2026-01-01T00:00:00.000Z", """{"id":1}"""),
        CachedFacility(2, "Alpha Prison", "alpha prison", "United States", "relay_only", false, "2026-02-01T00:00:00.000Z", """{"id":2}"""),
      ),
      groups = listOf(
        group(1, "Test Chapter", listOf("letter_writing_nights", "legal_support"), "both"),
        group(2, "Relay Only", listOf("international_relay"), "relay"),
        group(3, "Collectors", listOf("legal"), "collecting"),
      ),
      meta = listOf(DirectoryMeta("savedAt", "2026-09-19T08:00:00Z")),
    )
  }

  @After fun tearDown() = db.close()

  private suspend fun prisoners(q: String = "", status: String? = null, country: String? = null, featured: Boolean? = null, facility: Int? = null, sort: String = "name") =
    dao.prisoners(q, status, country, featured, facility, sort, 20, 0).map { it.removePrefix("""{"id":""").removeSuffix("}").toInt() }

  @Test
  fun search_matches_birth_or_chosen_name_as_a_substring_whatever_the_case() = runTest {
    assertEquals(listOf(1, 3), prisoners(q = "smith"))          // "Jane Smith"/"John Smith", and "Michael Smithson" by birth name
    assertEquals(listOf(1), prisoners(q = "john smith"))        // birth name only
    assertEquals(listOf(2), prisoners(q = "johnson"))
    assertEquals(3, dao.countPrisoners("", null, null, null, null))
    assertEquals(2, dao.countPrisoners("smith", null, null, null, null))
  }

  @Test
  fun filters_combine_and_a_null_filter_means_any() = runTest {
    assertEquals(listOf(2), prisoners(status = "pretrial"))
    assertEquals(listOf(3), prisoners(country = "Belarus"))
    assertEquals(listOf(1), prisoners(featured = true))
    assertEquals(listOf(2, 3), prisoners(facility = 2).sorted())
    assertEquals(emptyList<Int>(), prisoners(q = "smith", status = "pretrial"))
    assertEquals(listOf(3), prisoners(q = "smith", facility = 2))
  }

  @Test
  fun sorts_by_name_without_regard_to_case_by_newest_by_oldest_and_by_id_otherwise() = runTest {
    assertEquals(listOf(2, 1, 3), prisoners(sort = "name"))     // alex johnson, Jane Smith, Zed
    assertEquals(listOf(3, 2, 1), prisoners(sort = "newest"))
    assertEquals(listOf(1, 2, 3), prisoners(sort = "oldest"))
    assertEquals(listOf(1, 2, 3), prisoners(sort = "something the app does not know"))
  }

  @Test
  fun pages_do_not_overlap() = runTest {
    val first = dao.prisoners("", null, null, null, null, "name", 2, 0)
    val second = dao.prisoners("", null, null, null, null, "name", 2, 2)
    assertEquals(2, first.size); assertEquals(1, second.size)
    assertEquals(3, (first + second).toSet().size)
  }

  @Test
  fun facilities_filter_by_routing_and_by_having_a_relay_group() = runTest {
    suspend fun ids(routing: String? = null, relay: Boolean? = null) = dao.facilities("", null, routing, relay, "name", 20, 0)
    assertEquals(listOf("""{"id":2}"""), ids(routing = "relay_only"))
    assertEquals(listOf("""{"id":1}"""), ids(relay = true))
    assertEquals(listOf("""{"id":2}"""), ids(relay = false))
    assertEquals(1, dao.countFacilities("alpha", null, null, null))
  }

  @Test
  fun a_service_matches_a_whole_key_never_half_of_one_and_both_answers_to_either_role() = runTest {
    suspend fun ids(service: String? = null, role: String? = null) = dao.groups("", null, service, role, "name", 20, 0)
    assertEquals(listOf("""{"id":3}"""), ids(service = "legal"))                 // not "legal_support"
    assertEquals(listOf("""{"id":1}"""), ids(service = "legal_support"))
    assertEquals(listOf("""{"id":2}""", """{"id":1}"""), ids(role = "relay"))     // Relay Only, Test Chapter (both)
    assertEquals(listOf("""{"id":3}""", """{"id":1}"""), ids(role = "collecting"))
  }

  @Test
  fun a_new_download_replaces_the_old_one_whole() = runTest {
    dao.replaceAll(listOf(prisoner(9, "Only One", null)), emptyList(), emptyList(), listOf(DirectoryMeta("savedAt", "2026-09-20T08:00:00Z")))
    assertEquals(listOf(9), prisoners())
    assertEquals(DirectoryCounts(1, 0, 0), dao.observeCounts().first())
    assertEquals("2026-09-20T08:00:00Z", dao.meta("savedAt"))
  }
}

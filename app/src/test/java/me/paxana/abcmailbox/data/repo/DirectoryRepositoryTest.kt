package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.DirectoryApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The repository against a fake server: right URL, right query string, right mapping. */
class DirectoryRepositoryTest {

  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private lateinit var repo: DefaultDirectoryRepository

  @Before
  fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/"))
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build().create(DirectoryApi::class.java)
    repo = DefaultDirectoryRepository(api, json)
  }

  @After
  fun tearDown() = server.shutdown()

  private fun fixture(name: String) = javaClass.getResourceAsStream("/directory/$name")!!.reader().readText()

  @Test
  fun `featured prisoners asks for featured=true and maps`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("prisoners-page.json")))
    val r = repo.featuredPrisoners(limit = 6)
    val req = server.takeRequest()
    assertEquals("/prisoner/prisoners", req.requestUrl?.encodedPath)
    assertEquals("true", req.requestUrl?.queryParameter("featured"))
    assertEquals("6", req.requestUrl?.queryParameter("page_size"))
    assertTrue(r is ApiResult.Success && r.value.size == 3)
  }

  @Test
  fun `single reads send id and full=true`() = runTest {
    server.enqueue(MockResponse().setBody(fixture("prison-full.json")))
    val r = repo.facility(2)
    val req = server.takeRequest()
    assertEquals("/prison/prison", req.requestUrl?.encodedPath)
    assertEquals("2", req.requestUrl?.queryParameter("id"))
    assertEquals("true", req.requestUrl?.queryParameter("full"))
    assertEquals("Alpha Prison", (r as ApiResult.Success).value.name)
  }

  @Test
  fun `a 404 becomes NotFound`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"info":"Prisoner not found."}"""))
    val r = repo.prisoner(999)
    assertTrue(r is ApiResult.Failure)
    assertEquals("Prisoner not found.", (r as ApiResult.Failure).error.userMessage)
  }
}

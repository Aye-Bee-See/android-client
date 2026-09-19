package me.paxana.abcmailbox.data.repo

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import java.time.Instant
import me.paxana.abcmailbox.data.api.PrisonerDto
import me.paxana.abcmailbox.data.api.PrisonDto
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.offline.FakeOfflineDirectory
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
  private val offline = FakeOfflineDirectory()
  private var noSignal = false

  @Before
  fun setUp() {
    server.start()
    // "No signal" is an interceptor that throws what a phone without a connection throws.
    val client = OkHttpClient.Builder().addInterceptor { chain -> if (noSignal) throw java.net.UnknownHostException("no signal") else chain.proceed(chain.request()) }.build()
    val api = Retrofit.Builder().baseUrl(server.url("/")).client(client)
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build().create(DirectoryApi::class.java)
    repo = DefaultDirectoryRepository(api, json, offline)
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

  // Without a connection ---------------------------------------------------------------------------

  private val savedOn = Instant.parse("2026-09-19T08:00:00Z")
  private fun goOffline() { noSignal = true }
  private fun comeBackOnline() {
    noSignal = false
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
        if (request.path!!.startsWith("/prison/mail-rules")) """{"data":{"categories":[],"rules":[]},"success":true,"status":200}""" else fixture("prisoners-page.json"))
    }
  }

  @Test
  fun `with no connection a saved record is shown, and the repository says it is the saved copy`() = runTest {
    offline.at = savedOn
    offline.savedFacilities += PrisonDto(id = 2, prisonName = "Alpha Prison", mailRules = listOf("no_photos"))
    goOffline()
    val r = repo.facility(2) as ApiResult.Success
    assertEquals("Alpha Prison", r.value.name)
    assertEquals("the compiled rule list still names the rule", "No pictures", r.value.rules.rules.single().label)
    assertEquals(DirectorySource.Saved(savedOn), repo.source.value)
  }

  @Test
  fun `with no connection and nothing saved, the screen gets the network error, not an empty page`() = runTest {
    goOffline()
    assertTrue((repo.facility(2) as ApiResult.Failure).error is AppError.Network)
    offline.at = savedOn // a copy exists, but this record is not in it
    assertTrue((repo.prisoner(99) as ApiResult.Failure).error is AppError.Network)
    assertEquals(DirectorySource.Live, repo.source.value)
  }

  @Test
  fun `the server's own refusal stands, even when the phone has a copy of the record`() = runTest {
    offline.at = savedOn
    offline.savedPrisoners += PrisonerDto(id = 7, chosenName = "Withdrawn since the download")
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"info":"Prisoner 7 not found","status":404}"""))
    assertTrue((repo.prisoner(7) as ApiResult.Failure).error is AppError.NotFound)
  }

  @Test
  fun `a read that reaches the server again flips the source back to live`() = runTest {
    offline.at = savedOn
    offline.savedPrisoners += PrisonerDto(id = 1, chosenName = "Jane", featured = true)
    goOffline()
    assertEquals(listOf("Jane"), (repo.featuredPrisoners() as ApiResult.Success).value.map { it.name })
    assertEquals(PrisonerFilter(featured = true, sort = "newest"), offline.asked.last())
    assertTrue(repo.source.value is DirectorySource.Saved)

    comeBackOnline()
    assertEquals(3, (repo.featuredPrisoners() as ApiResult.Success).value.size)
    assertEquals(DirectorySource.Live, repo.source.value)
  }

  @Test
  fun `a wifi login page answering in place of the API counts as no connection`() = runTest {
    offline.at = savedOn
    offline.savedFacilities += PrisonDto(id = 2, prisonName = "Alpha Prison")
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Welcome to Community Centre WiFi. Accept the terms to continue.</body></html>")
    }
    assertEquals("Alpha Prison", (repo.facility(2) as ApiResult.Success).value.name)
    assertEquals(DirectorySource.Saved(savedOn), repo.source.value)
  }
}

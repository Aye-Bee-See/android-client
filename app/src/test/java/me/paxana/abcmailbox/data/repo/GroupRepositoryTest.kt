package me.paxana.abcmailbox.data.repo

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.Verification
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.letters.ComposeViewModelTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class GroupRepositoryTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private lateinit var repo: DefaultGroupRepository

  @Before
  fun setUp() {
    server.start()
    val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    val codec = LetterCodec(FixedMode(EncryptionMode.SERVER), FakeCryptoEngine(), InMemoryVault(), FakeSessionRepository(), retrofit.create(AuthApi::class.java), json)
    repo = DefaultGroupRepository(retrofit.create(GroupApi::class.java), ComposeViewModelTest.FakeLetters(), NoDirectory, codec, json)
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun `a status move sends id and status, and a refusal surfaces the API's own sentence`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"id":41,"chat":41,"sender":"user","prisoner":1,"user":4,"status":"printed","relayChapter":1,"messageText":"Hi"},"success":true,"status":200}"""))
    val ok = repo.setStatus(41, LetterStatus.PRINTED) as ApiResult.Success
    assertEquals(LetterStatus.PRINTED, ok.value.status)
    val req = server.takeRequest()
    assertEquals("PUT", req.method); assertEquals("/messaging/status", req.path)
    assertEquals("""{"id":41,"status":"printed"}""", req.body.readUtf8())

    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"LetterStatusError","info":"Error updating letter status.","status":409,"error":"A printed letter cannot move to queued."}"""))
    val refused = repo.setStatus(41, LetterStatus.QUEUED) as ApiResult.Failure
    assertEquals("A printed letter cannot move to queued.", refused.error.userMessage)
  }

  @Test
  fun `writers hide the placeholder address and know whether a token is live`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":[
      {"id":44,"name":"Zed","username":"writer-1","email":"writer-1@managed.example","managedBy":1,"managerNote":"Tuesdays","claimToken":{"expiresAt":"2999-01-01T00:00:00.000Z"}},
      {"id":45,"name":"alex","username":"writer-2","email":"alex@riseup.net","managedBy":1,"claimToken":null},
      {"id":46,"name":"Old","username":"writer-3","managedBy":1,"claimToken":{"expiresAt":"2001-01-01T00:00:00.000Z"}},
      {"id":48,"name":"Anonymous writer","username":"anon-1","managedBy":1,"anonymousForChapter":1,"claimToken":null}],"success":true,"status":200}"""))
    val writers = (repo.writers() as ApiResult.Success).value
    // Sorted without regard to case; the group's shared anonymous account is not a person to hand off.
    assertEquals(listOf("alex", "Old", "Zed"), writers.map { it.name })
    val zed = writers.last()
    assertNull(zed.email); assertEquals("Tuesdays", zed.note); assertTrue(zed.hasLiveToken)
    assertEquals("alex@riseup.net", writers.first().email); assertFalse(writers.first().hasLiveToken)
    assertFalse("an expired token is not live", writers[1].hasLiveToken)
  }

  @Test
  fun `adding a writer trims and omits blanks, and a token is returned once`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":47,"name":"Maria T.","managedBy":1},"success":true,"status":201}"""))
    repo.addWriter("  Maria T. ", "  ", "")
    assertEquals("""{"name":"Maria T."}""", server.takeRequest().body.readUtf8())

    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"writer":47,"token":"R60GRVGC3V007NS41T6ZAPXG","expiresAt":"2026-09-20T22:20:47.692Z"},"success":true,"status":201}"""))
    val t = (repo.issueToken(47) as ApiResult.Success).value
    assertEquals("R60GRVGC3V007NS41T6ZAPXG", t.token)
    assertEquals("""{"writer":47}""", server.takeRequest().body.readUtf8())

    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    assertTrue(repo.revokeToken(47) is ApiResult.Success)
    val revoke = server.takeRequest()
    assertEquals("DELETE", revoke.method); assertEquals("""{"writer":47}""", revoke.body.readUtf8())
  }

  @Test
  fun `a group whose account is not active sees the API's explanation`() = runTest {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"info":"Your group is pending approval by a network admin.","status":403}"""))
    val r = repo.writers() as ApiResult.Failure
    assertEquals(AppError.Forbidden("Your group is pending approval by a network admin."), r.error)
  }

  private object NoDirectory : DirectoryRepository {
    override fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>> = emptyFlow()
    override fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>> = emptyFlow()
    override fun groups(filter: GroupFilter): Flow<PagingData<Group>> = emptyFlow()
    override suspend fun featuredPrisoners(limit: Int) = ApiResult.Success(emptyList<Prisoner>())
    override suspend fun prisoner(id: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun facility(id: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun group(id: Int) = ApiResult.Failure(AppError.NotFound(null))
  }
}

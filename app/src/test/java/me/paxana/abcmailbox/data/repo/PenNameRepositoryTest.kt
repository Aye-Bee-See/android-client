package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.session.SessionStore
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
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

class PenNameRepositoryTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val store = object : SessionStore {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow
    override suspend fun save(session: Session) { flow.value = session }
    override suspend fun clear() { flow.value = null }
  }
  private lateinit var repo: DefaultPenNameRepository

  @Before fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    repo = DefaultPenNameRepository(api, store, json, TestStrings())
  }
  @After fun tearDown() = server.shutdown()

  @Test
  fun `the check sends the name as the server stores it, and answers why a name is not free`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"available":false,"name":"James Hollow","reason":"That pen name is taken.","twoParts":true},"success":true,"status":200}"""))
    val r = (repo.check("  james   hollow ") as ApiResult.Success).value
    assertEquals("/auth/pen-name-available?name=james%20hollow", server.next().path)
    assertFalse(r.available); assertEquals("That pen name is taken.", r.reason); assertEquals("James Hollow", r.name)
  }

  @Test
  fun `the account's names come current first, and a new name updates the session's copy of the user`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"penName":"James Hollow","names":[{"name":"James Hollow","current":true,"since":"2026-09-23T10:00:00.000Z"},{"name":"Jim H","current":false,"since":"2026-09-01T10:00:00.000Z"}]},"success":true,"status":200}"""))
    val names = (repo.names() as ApiResult.Success).value
    assertEquals("James Hollow", names.current); assertEquals(listOf(true, false), names.names.map { it.current }); assertEquals("2026-09-01T10:00:00Z", names.names[1].since.toString())

    val user = SessionUser(7, "carol", "Carol", null, "user", null)
    store.save(Session("jwt-1", 0L, user, olderAccount = false))
    server.enqueue(MockResponse().setBody("""{"data":{"updatedRows":[1]},"success":true,"status":200}"""))
    server.next()
    assertEquals("Carol Hollow", (repo.set(" Carol  Hollow ") as ApiResult.Success).value)
    val put = server.next(); assertEquals("PUT", put.method); assertEquals("""{"id":7,"penName":"Carol Hollow"}""", put.body.readUtf8())
    assertEquals("Carol Hollow", store.flow.value?.user?.penName); assertEquals("the token stands", "jwt-1", store.flow.value?.token)
  }

  @Test
  fun `signed out, nothing is sent`() = runTest {
    assertTrue(repo.set("Carol Hollow") is ApiResult.Failure); assertEquals(0, server.requestCount); assertNull(store.flow.value)
  }
}

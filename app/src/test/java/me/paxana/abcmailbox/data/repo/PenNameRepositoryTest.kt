package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.domain.PenNames
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
    server.enqueue(MockResponse().setBody("""{"data":{"penName":"Carol Hollow","names":[{"name":"Carol Hollow","current":true}]},"success":true,"status":200}"""))
    server.next()
    assertEquals("Carol Hollow", (repo.set(" Carol  Hollow ") as ApiResult.Success).value)
    val put = server.next(); assertEquals("PUT", put.method); assertEquals("""{"id":7,"penName":"Carol Hollow"}""", put.body.readUtf8())
    assertEquals("Carol Hollow", store.flow.value?.user?.penName); assertEquals("the token stands", "jwt-1", store.flow.value?.token)
  }

  @Test
  fun `the limits come with the names, and an older API without them gets its own settings`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"penName":"Jim H","names":[{"name":"Jim H","current":true,"since":"2026-09-20T10:00:00.000Z"}],"changeAllowedAt":"2026-12-19T10:00:00.000Z","newNamesLeft":1,"newNamesWindowEnds":"2027-09-20T10:00:00.000Z","cooldownDays":90,"newPerYear":2},"success":true,"status":200}"""))
    val n = (repo.names() as ApiResult.Success).value
    assertEquals("2026-12-19T10:00:00Z", n.changeAllowedAt.toString()); assertEquals(1, n.newNamesLeft)
    assertEquals("2027-09-20T10:00:00Z", n.newNamesWindowEnds.toString()); assertEquals(90, n.cooldownDays)
    assertFalse(n.canChange(java.time.Instant.parse("2026-12-19T09:59:59Z"))); assertTrue(n.canChange(java.time.Instant.parse("2026-12-19T10:00:00Z")))

    server.enqueue(MockResponse().setBody("""{"data":{"penName":null,"names":[]},"success":true,"status":200}"""))
    val older = (repo.names() as ApiResult.Success).value
    assertNull(older.changeAllowedAt); assertEquals(2, older.newNamesLeft); assertTrue(older.canChange())
  }

  @Test
  fun `a change the limits refuse is told apart by its condition`() = runTest {
    store.save(Session("jwt", 0L, SessionUser(7, "carol", "Carol", null, "user", null)))
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"PenNameLimitError","info":"A pen name may be changed once every 90 days.","status":409,"condition":"new_names"}"""))
    assertEquals("new_names", (repo.set("Anna Hollow") as ApiResult.Failure).error.penNameLimit)
    assertNull("any other 409 is not a limit", AppError.Conflict("x", name = "KeyVersionError").penNameLimit)
    assertEquals("the session is not changed", null, store.flow.value?.user?.penName)
  }

  @Test
  fun `going back to an old name takes the spelling it was first given, not the one typed`() = runTest {
    store.save(Session("jwt", 0L, SessionUser(7, "carol", "Carol", null, "user", null)))
    server.enqueue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setBody("""{"data":{"penName":"Anna Hollow","names":[{"name":"Anna Hollow","current":true}]},"success":true,"status":200}"""))
    assertEquals(ApiResult.Success("Anna Hollow"), repo.set("anna  hollow"))
    assertEquals("Anna Hollow", store.flow.value?.user?.penName)
  }

  @Test
  fun `a cooldown of 0 days allows a change at once`() {
    val justNow = java.time.Instant.now().plusSeconds(5)
    assertFalse(PenNames("A b", emptyList(), changeAllowedAt = justNow).canChange())
    assertTrue(PenNames("A b", emptyList(), changeAllowedAt = justNow, cooldownDays = 0).canChange())
  }

  @Test
  fun `signed out, nothing is sent`() = runTest {
    assertTrue(repo.set("Carol Hollow") is ApiResult.Failure); assertEquals(0, server.requestCount); assertNull(store.flow.value)
  }
}

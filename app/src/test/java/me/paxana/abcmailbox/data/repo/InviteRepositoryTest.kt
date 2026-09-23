package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class InviteRepositoryTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val sessions = FakeSessionRepository()
  private lateinit var repo: DefaultInviteRepository
  private val kept = object : PendingInvitesStore {
    var issued: me.paxana.abcmailbox.domain.IssuedInvites? = null
    override suspend fun save(issued: me.paxana.abcmailbox.domain.IssuedInvites) { this.issued = issued }
    override suspend fun load() = issued
    override suspend fun clear() { issued = null }
  }

  @Before fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(GroupApi::class.java)
    repo = DefaultInviteRepository(api, sessions, json, TestStrings(), kept)
    sessions.signInAs(SessionUser(9, "member1", "Sam", null, "chapter", 1))
  }
  @After fun tearDown() = server.shutdown()

  @Test
  fun `issuing reads the group's name first, then posts the count and the trimmed label, and keeps the codes for the slips`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"id":1,"name":"Portland ABC"},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"chapter":1,"batch":"k3Zp0Q9x","label":"Letter night","expiresAt":"2026-10-22T19:00:00.000Z","codes":["7Q4M-2XKD-9HBT","2B9X-K4NM-7PQR"],"outstanding":2,"limit":20},"info":"Invite codes issued. They are shown once.","success":true,"status":201}"""))
    val r = repo.issue(2, "  Letter night ", null) as ApiResult.Success
    assertEquals("/chapter/chapter?id=1", server.next().path)
    val post = server.next()
    assertEquals("POST", post.method); assertEquals("/auth/invite-codes", post.path)
    assertEquals("""{"count":2,"label":"Letter night"}""", post.body.readUtf8())
    assertEquals(listOf("7Q4M-2XKD-9HBT", "2B9X-K4NM-7PQR"), r.value.codes)
    assertEquals("Portland ABC", r.value.groupName); assertEquals("k3Zp0Q9x", r.value.batch); assertEquals(2, r.value.outstanding); assertEquals(20, r.value.limit)
    assertEquals("2026-10-22T19:00:00Z", r.value.expiresAt.toString())
    // Kept on the phone until the person has the slips: a process death in between loses nothing.
    assertEquals(r.value, repo.pending()); repo.finished(); assertNull(repo.pending())
  }

  @Test
  fun `over the quota, the server's sentence is the error`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"id":1,"name":"Portland ABC"},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"InviteQuotaError","info":"Conflict","error":"The chapter has 18 unused codes and may have 20; 5 more would go over.","status":409}"""))
    val r = repo.issue(5, null, 7) as ApiResult.Failure
    server.next(); assertEquals("""{"count":5,"days":7}""", server.next().body.readUtf8())
    assertTrue(r.error is AppError.Conflict); assertTrue(r.error.userMessage!!.contains("18 unused"))
  }

  @Test
  fun `the list is counts only, and cancelling names one batch or all`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"chapter":1,"outstanding":17,"limit":20,"batches":[{"batch":"k3Zp0Q9x","label":"Letter night","createdAt":"2026-09-22T19:00:00.000Z","expiresAt":"2026-10-22T19:00:00.000Z","total":20,"used":3,"cancelled":0,"expired":0,"unused":17}]},"success":true,"status":200}"""))
    val q = (repo.quota() as ApiResult.Success).value
    assertEquals(17, q.outstanding); assertEquals(20, q.limit)
    val b = q.batches.single(); assertEquals("Letter night", b.label); assertEquals(3, b.used); assertEquals(17, b.unused); assertEquals(20, b.total)

    server.enqueue(MockResponse().setBody("""{"data":{"chapter":1,"cancelled":17,"outstanding":0},"success":true,"status":200}"""))
    assertEquals(17, (repo.cancel("k3Zp0Q9x") as ApiResult.Success).value)
    server.next(); val one = server.next(); assertEquals("DELETE", one.method); assertEquals("""{"batch":"k3Zp0Q9x"}""", one.body.readUtf8())
    server.enqueue(MockResponse().setBody("""{"data":{"chapter":1,"cancelled":0,"outstanding":0},"success":true,"status":200}"""))
    repo.cancel(null); assertEquals("""{"all":true}""", server.next().body.readUtf8())
  }

  @Test
  fun `a writer, or a superadmin with no group, is refused without a request`() = runTest {
    sessions.signInAs(SessionUser(3, "admin", null, null, "admin", null))
    assertTrue((repo.quota() as ApiResult.Failure).error is AppError.Forbidden)
    assertTrue((repo.issue(1, null, null) as ApiResult.Failure).error is AppError.Forbidden)
    assertEquals(0, server.requestCount); assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
  }
}

package me.paxana.abcmailbox.data.repo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.next
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

class ReferenceRepositoryTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private lateinit var repo: DefaultReferenceRepository

  @Before fun setUp() {
    server.start()
    repo = DefaultReferenceRepository(Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(LettersApi::class.java), json)
  }
  @After fun tearDown() = server.shutdown()

  @Test
  fun `a number looks up to whose letter it was, and the digits go without dashes`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"reference":"482719356","letter":{"id":41,"chat":9,"status":"mailed","paper":false,"createdAt":"2026-09-20T10:00:00.000Z"},"mailedAt":"2026-09-21T10:00:00.000Z","chat":9,"writer":{"id":4,"penName":"James Hollow","name":"Jim","anonymous":false},"prisoner":{"id":3,"birthName":"Alex Smith","chosenName":"Alex"},"careOf":{"id":1,"name":"PDX ABC"}},"success":true,"status":200}"""))
    val r = (repo.lookup("4827 1935 6") as ApiResult.Success).value
    assertEquals("/messaging/reference?number=482719356", server.next().path)
    assertEquals("4827-1935-6", r.reference); assertEquals(41, r.letter?.id); assertEquals(LetterStatus.MAILED, r.letter?.status); assertEquals(9, r.chatId)
    assertEquals("James Hollow", r.writer.displayName); assertEquals("the chosen name first", "Alex", r.prisoner?.name); assertEquals("PDX ABC", r.careOfName)
    assertEquals("2026-09-21T10:00:00Z", r.mailedAt.toString())
  }

  @Test
  fun `a wrong check digit and a number that is not this group's carry their conditions`() = runTest {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"name":"ReplyReferenceError","info":"Bad Request","error":"That number has a mistake in it. Check it against the letter.","status":400,"condition":"checksum"}"""))
    val bad = (repo.lookup("482719355") as ApiResult.Failure).error as AppError.Validation
    assertEquals("checksum", bad.condition)
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"name":"ReplyReferenceError","info":"Not this chapter's reference.","status":404,"condition":"unknown"}"""))
    val unknown = (repo.lookup("482719356") as ApiResult.Failure).error as AppError.NotFound
    assertEquals("unknown", unknown.condition)
  }

  @Test
  fun `writers are found by any name they have used, and a former name is said`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":[{"id":4,"penName":"James Hollow","name":"Jim","anonymous":false,"matched":{"name":"Jim H","current":false}},{"id":9,"penName":null,"name":null,"anonymous":true,"matched":null}],"success":true,"status":200}"""))
    val r = (repo.writers(" jim ") as ApiResult.Success).value
    assertEquals("/messaging/writers?name=jim", server.next().path)
    assertEquals("James Hollow", r[0].displayName); assertEquals("Jim H", r[0].matchedName); assertFalse(r[0].matchedIsCurrent)
    assertTrue(r[1].anonymous); assertNull(r[1].matchedName)
  }
}

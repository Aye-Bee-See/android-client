package me.paxana.abcmailbox.data.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.session.SessionCache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionInterceptorTest {

  private val server = MockWebServer()
  private val cache = SessionCache()
  private lateinit var client: OkHttpClient

  @Before
  fun setUp() {
    server.start()
    client = OkHttpClient.Builder().addInterceptor(SessionInterceptor(cache)).build()
  }

  @After
  fun tearDown() = server.shutdown()

  private fun get() = client.newCall(Request.Builder().url(server.url("/chat/chats")).build()).execute()

  @Test
  fun `no header without a session`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    get().close()
    assertNull(server.takeRequest().getHeader("Authorization"))
  }

  @Test
  fun `bearer header with a session`() {
    cache.token = "abc.def.ghi"
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    get().close()
    assertEquals("Bearer abc.def.ghi", server.takeRequest().getHeader("Authorization"))
  }

  @Test
  fun `401 with a token reports that token`() = runTest {
    cache.token = "stale"
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"info":"Invalid token."}"""))
    get().close()
    assertEquals("stale", cache.unauthorized.first())
  }

  @Test
  fun `401 without a token is not a session event`() {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"info":"Incorrect username or password."}"""))
    val response = get()
    assertEquals(401, response.code)
    response.close()
    assertEquals(0, cache.unauthorized.replayCache.size)
  }
}

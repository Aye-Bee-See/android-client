package me.paxana.abcmailbox.data.dev

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevServerTest {

  @Test
  fun `normalise accepts the forms a person types`() {
    assertEquals("http://192.168.1.20:3000/", DevServerRepository.normalise("192.168.1.20"))
    assertEquals("http://192.168.1.20:3000/", DevServerRepository.normalise(" 192.168.1.20:3000 "))
    assertEquals("http://192.168.1.20:8080/", DevServerRepository.normalise("http://192.168.1.20:8080/some/path?x=1"))
    assertEquals("https://api.abcmailbox.net/", DevServerRepository.normalise("https://api.abcmailbox.net"))
    assertEquals("http://mymac.local:3000/", DevServerRepository.normalise("mymac.local"))
    assertNull(DevServerRepository.normalise(""))
    assertNull(DevServerRepository.normalise("not a url at all"))
  }

  @Test
  fun `interceptor rewrites host and port but keeps the path and query`() {
    val server = MockWebServer().apply { start() }
    val client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor { server.url("/").toString() }).build()
    server.enqueue(MockResponse().setBody("{}"))
    client.newCall(Request.Builder().url("http://10.0.2.2:3000/prisoner/prisoners?q=ales&page=2").build()).execute().close()
    val req = server.takeRequest()
    assertEquals("/prisoner/prisoners?q=ales&page=2", req.path)
    assertEquals(server.hostName, req.requestUrl?.host)
    assertEquals(server.port, req.requestUrl?.port)
    server.shutdown()
  }
}

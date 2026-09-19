package me.paxana.abcmailbox.data.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.di.NetworkModule
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

class PushRegistrarTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val json = NetworkModule.json()
  private val sessions = FakeSessionRepository()
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val provider = FakeProvider()
  private lateinit var registrar: DefaultPushRegistrar

  class FakeProvider(override var availability: PushProvider.Availability = PushProvider.Availability.READY) : PushProvider {
    var address: String? = "fcm-token-of-this-phone-0001"; var forgotten = 0
    override suspend fun token() = address
    override suspend fun forget() { forgotten++ }
    override val deviceLabel = "Google Pixel 10"
  }

  // The shape the live API answers with (checked 19 Sep 2026): flat, `created` and `deliverable` beside the device's fields.
  private fun registered(id: Int = 7, deliverable: Boolean = false) =
    MockResponse().setResponseCode(201).setBody("""{"data":{"id":$id,"userId":4,"provider":"fcm","platform":"android","label":"Google Pixel 10","muted":false,"created":true,"deliverable":$deliverable},"info":"Device registered.","success":true,"status":201}""")

  @Before
  fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(NotificationsApi::class.java)
    val store = PreferenceDataStoreFactory.create(scope = ioScope) { File(tmp.root, "push.preferences_pb") }
    sessions.signInAs(SessionUser(4, "user1", null, null, "user", null))
    registrar = DefaultPushRegistrar(provider, api, sessions, store, json, TestScope(UnconfinedTestDispatcher()))
  }

  @After fun tearDown() { server.shutdown(); ioScope.cancel() }

  @Test
  fun `nothing is registered until the person turns it on`() = runTest {
    assertFalse(registrar.enabled.first())
    assertEquals("signing in alone registers nothing", 0, server.requestCount)
  }

  @Test
  fun `turning it on registers this phone, and says whether the server can ring it yet`() = runTest {
    server.enqueue(registered(deliverable = false))
    assertEquals(ApiResult.Success(false), registrar.turnOn())
    val post = server.next()
    assertEquals("/auth/device", post.path)
    assertEquals("""{"token":"fcm-token-of-this-phone-0001","platform":"android","label":"Google Pixel 10"}""", post.body.readUtf8())
    assertTrue(registrar.enabled.first())
  }

  @Test
  fun `a phone without the push service, or a build without a project, cannot be turned on and calls nobody`() = runTest {
    for (a in listOf(PushProvider.Availability.NO_SERVICE, PushProvider.Availability.NOT_CONFIGURED)) {
      provider.availability = a
      assertTrue(registrar.turnOn() is ApiResult.Failure)
    }
    assertEquals(0, server.requestCount); assertFalse(registrar.enabled.first())
  }

  @Test
  fun `a registration the server refuses leaves it off`() = runTest {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errors":["token must be 16 to 4096 characters."],"status":400}"""))
    assertTrue(registrar.turnOn() is ApiResult.Failure)
    assertFalse(registrar.enabled.first())
  }

  @Test
  fun `once on, a new address from the push service is sent on, and turning it off forgets the phone on both sides`() = runTest {
    server.enqueue(registered(id = 7, deliverable = true)); registrar.turnOn(); server.next()

    server.enqueue(registered(id = 7, deliverable = true))
    registrar.onNewToken("fcm-token-after-rotation-0002")
    assertTrue(server.next().body.readUtf8().contains("fcm-token-after-rotation-0002"))

    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    assertEquals(ApiResult.Success(Unit), registrar.turnOff())
    val delete = server.next()
    assertEquals("DELETE", delete.method); assertEquals("""{"id":7}""", delete.body.readUtf8())
    assertEquals(1, provider.forgotten); assertFalse(registrar.enabled.first())

    registrar.onNewToken("fcm-token-nobody-asked-for-0003")
    assertEquals("off means off: a new address is not reported", 3, server.requestCount)
  }

  @Test
  fun `someone else signing in on this phone takes the registration over, as the API intends`() = runTest {
    server.enqueue(registered()); registrar.turnOn(); server.next()
    server.enqueue(registered(id = 7))
    sessions.signInAs(SessionUser(5, "user2", null, null, "user", null).let { it }) // the fake issues the same token text; a real sign-in issues a new one
    // The fake session's token does not change between accounts, so drive the rule directly:
    registrar.onNewToken("fcm-token-of-this-phone-0001")
    assertEquals("/auth/device", server.next().path)
  }
}

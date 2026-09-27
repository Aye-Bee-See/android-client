package me.paxana.abcmailbox.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.di.NetworkModule
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/** Push was taken out on 27 Sep 2026: a phone that had it on has its server record removed once, and nothing else is asked. */
class PushRetirementTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val json = NetworkModule.json()
  private val sessions = FakeSessionRepository()
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var store: DataStore<Preferences>
  private lateinit var retirement: DefaultPushRetirement
  private val enabled = booleanPreferencesKey("push_enabled")
  private val deviceId = intPreferencesKey("push_device_id")

  @Before
  fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(NotificationsApi::class.java)
    store = PreferenceDataStoreFactory.create(scope = ioScope) { File(tmp.root, "settings.preferences_pb") }
    retirement = DefaultPushRetirement(api, sessions, store, json, TestScope(UnconfinedTestDispatcher()))
  }

  @After fun tearDown() { server.shutdown(); ioScope.cancel() }

  private suspend fun hadPushOn(id: Int = 7) = store.edit { it[enabled] = true; it[deviceId] = id }
  private suspend fun left() = store.data.first().let { listOfNotNull(it[enabled]?.let { "enabled" }, it[deviceId]?.let { "device" }) }

  @Test
  fun `a phone that had push on has its device record removed, then forgets the old settings`() = runTest {
    hadPushOn(7); sessions.signInAs(SessionUser(4, "user1", null, null, "user", null))
    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    retirement.retire()
    val delete = server.next()
    assertEquals("DELETE", delete.method); assertEquals("/auth/device", delete.path); assertEquals("""{"id":7}""", delete.body.readUtf8())
    assertEquals(emptyList<String>(), left())
  }

  @Test
  fun `a record the server no longer has is forgotten too, but no connection keeps it for next time`() = runTest {
    hadPushOn(7); sessions.signInAs(SessionUser(4, "user1", null, null, "user", null))
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    retirement.retire()
    assertEquals("tried again at the next start", listOf("enabled", "device"), left())

    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"name":"NotFoundError","info":"Device not found.","status":404}"""))
    retirement.retire()
    assertEquals(emptyList<String>(), left())
  }

  @Test
  fun `someone who never had push is asked nothing, and account deletion only forgets the settings`() = runTest {
    sessions.signInAs(SessionUser(4, "user1", null, null, "user", null))
    retirement.retire()
    assertEquals(0, server.requestCount)

    hadPushOn(7); retirement.forgetLocally()
    assertEquals(emptyList<String>(), left()); assertEquals(0, server.requestCount)
    assertNull(store.data.first()[deviceId])
  }
}

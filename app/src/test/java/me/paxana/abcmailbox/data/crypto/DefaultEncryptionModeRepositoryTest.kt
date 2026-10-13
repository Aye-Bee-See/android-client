package me.paxana.abcmailbox.data.crypto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.HealthApi
import me.paxana.abcmailbox.data.dev.DevServerUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/** A server that spoke end-to-end and then says server mode is remembered as having gone down, across restarts. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultEncryptionModeRepositoryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") } }
  private lateinit var api: HealthApi

  @Before fun setUp() { server.start(); api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HealthApi::class.java) }
  @After fun tearDown() = server.shutdown()

  private fun health(mode: String) = MockResponse().setBody("""{"status":"ok","encryptionMode":"$mode","push":[]}""")
  private fun repo() = DefaultEncryptionModeRepository(api, json, store, DevServerUrl())

  @Test
  fun `end-to-end, then server mode, is a downgrade, remembered across a restart and until the server seals again`() = runTest {
    val modes = repo()
    server.enqueue(health("e2e")); assertEquals(EncryptionMode.E2E, modes.refresh()); assertEquals(false, modes.downgraded.value)
    server.enqueue(health("server")); assertEquals(EncryptionMode.SERVER, modes.refresh()); assertEquals(true, modes.downgraded.value)

    val restarted = repo() // a new process: the memory is on disk
    server.enqueue(health("server")); restarted.refresh(); assertEquals(true, restarted.downgraded.value)
    server.enqueue(health("e2e")); restarted.refresh(); assertEquals(false, restarted.downgraded.value)
  }

  @Test
  fun `a server that has only ever spoken server mode is not a downgrade`() = runTest {
    val modes = repo()
    server.enqueue(health("server")); assertEquals(EncryptionMode.SERVER, modes.refresh()); assertEquals(false, modes.downgraded.value)
  }
}

package me.paxana.abcmailbox.data.session

import me.paxana.abcmailbox.apiRequestCount
import me.paxana.abcmailbox.SchemeDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.queue
import me.paxana.abcmailbox.text.TestStrings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Deleting one's account (API PR #104). The server's answers below were recorded from the merged code on 20 Sep 2026. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val vault = InMemoryVault()
  private val store = object : SessionStore {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow
    override suspend fun save(session: Session) { flow.value = session }
    override suspend fun clear() { flow.value = null }
  }
  private lateinit var repo: DefaultSessionRepository

  @Before
  fun setUp() = runTest {
    server.start()
    server.dispatcher = SchemeDispatcher()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    repo = DefaultSessionRepository(store, api, SessionCache(), json, FixedMode(EncryptionMode.SERVER), FakeCryptoEngine(), vault, TestScope(UnconfinedTestDispatcher()), TestStrings(), FakeSchemeMemory())
    store.save(Session("jwt-1", 0L, SessionUser(7, "carol", null, null, "user", null)))
    vault.store(7, Sodium.KeyPair("PUB".toByteArray(), "PRIV".toByteArray()))
  }

  @After fun tearDown() = server.shutdown()

  /** The sign-in that proves the password, which now comes before every delete request. */
  private fun passwordAccepted() = server.queue(MockResponse().setBody("""{"data":{"user":{"id":7,"username":"carol","role":"user"},"token":{"token":"jwt-2","expires":1}},"success":true,"status":200}"""))

  @Test
  fun `the right password deletes the account, then the phone's share, then the session, in that order`() = runTest {
    passwordAccepted()
    server.queue(MockResponse().setBody("""{"data":{"deleted":1,"letters":3,"replies":1,"attachments":1,"threads":2},"info":"Successfully deleted user.","success":true,"status":200,"name":"user remove"}"""))
    var wipedFor: Int? = null
    val result = repo.deleteAccount("password1") { id ->
      wipedFor = id
      // The stores being wiped still need to know whose things these are; the screens must not have changed yet.
      assertNotNull("the session goes last", store.flow.value)
    }
    assertEquals("/auth/login", server.next().path)
    val request = server.next()
    assertEquals("DELETE", request.method); assertEquals("/auth/user", request.path)
    assertEquals("""{"id":7,"password":"password1"}""", request.body.readUtf8())
    assertEquals(3, (result as ApiResult.Success).value.letters); assertEquals(2, result.value.threads)
    assertEquals(7, wipedFor)
    assertNull(store.flow.value); assertNull("keys too", vault.keyPair(7))
  }

  @Test
  fun `a wrong password is caught by the phone, and no delete request is ever sent`() = runTest {
    // What the API answers a failed sign-in with. An old server would have deleted the account had it been asked.
    server.queue(MockResponse().setResponseCode(401).setBody("""{"success":false,"name":"AuthenticationError","info":"Unauthorized","status":401}"""))
    var wiped = false
    val result = repo.deleteAccount("not-it") { wiped = true }
    assertTrue((result as ApiResult.Failure).error is AppError.Forbidden)
    assertEquals("/auth/login", server.next().path)
    assertEquals("the sign-in was the only request", 1, server.apiRequestCount)
    assertTrue("nothing local was touched", !wiped)
    assertNotNull(store.flow.value); assertNotNull(vault.keyPair(7))
  }

  @Test
  fun `a server that checks the password itself and says no is believed too`() = runTest {
    passwordAccepted()
    server.queue(MockResponse().setResponseCode(403).setBody("""{"success":false,"name":"AuthorizationError","info":"The password is wrong; nothing was deleted.","status":403}"""))
    assertTrue((repo.deleteAccount("password1") as ApiResult.Failure).error is AppError.Forbidden)
    assertNotNull(store.flow.value)
  }

  @Test
  fun `a refusal arrives with its name, and changes nothing`() = runTest {
    passwordAccepted()
    server.queue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"AccountDeleteError","error":"This is the only admin account. Make another admin first, or nobody could run the site.","status":409}"""))
    val error = (repo.deleteAccount("password1") as ApiResult.Failure).error
    assertEquals("AccountDeleteError", (error as AppError.Conflict).name)
    assertNotNull(store.flow.value)
  }

  @Test
  fun `a phone failing to tidy up does not leave the person signed in to an account that is gone`() = runTest {
    passwordAccepted()
    server.queue(MockResponse().setBody("""{"data":{"deleted":1,"letters":0,"replies":0,"attachments":0,"threads":0},"success":true,"status":200,"name":"user remove"}"""))
    val result = repo.deleteAccount("password1") { error("disk full") }
    assertTrue(result is ApiResult.Success)
    assertNull(store.flow.value)
  }
}

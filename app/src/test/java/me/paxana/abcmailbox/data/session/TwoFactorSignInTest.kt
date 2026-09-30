package me.paxana.abcmailbox.data.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.SchemeDispatcher
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.SessionInterceptor
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.queue
import me.paxana.abcmailbox.text.TestStrings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

/** Two-factor sign-in (API #173, #175): a right password answers a challenge, and a code finishes the sign-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoFactorSignInTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val vault = InMemoryVault()
  private val cache = SessionCache()
  private val store = object : SessionStore {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow
    override suspend fun save(session: Session) { flow.value = session }
    override suspend fun clear() { flow.value = null }
  }
  private lateinit var repo: DefaultSessionRepository

  @Before fun setUp() { server.start(); server.dispatcher = SchemeDispatcher(SchemeDispatcher.split()) }
  @After fun tearDown() = server.shutdown()

  private fun build(mode: EncryptionMode = EncryptionMode.E2E): DefaultSessionRepository {
    val client = OkHttpClient.Builder().addInterceptor(SessionInterceptor(cache)).build()
    val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    return DefaultSessionRepository(store, api, cache, json, FixedMode(mode), FakeCryptoEngine(), vault, TestScope(UnconfinedTestDispatcher()), TestStrings(), FakeSchemeMemory()).also { repo = it }
  }
  private val challenge = """{"data":{"twoFactor":{"challenge":"CH-1","expiresAt":"2099-01-01T00:00:00.000Z"}},"success":true,"status":200}"""
  private fun session(extra: String = "") = """{"data":{"user":{"id":7,"username":"carol","role":"user"},"token":{"token":"jwt-1","expires":1},"keys":{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)underwrap(carolpass)with(SALT)","kdfSalt":"SALT","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864},"hasRecovery":true,"orgKey":null}$extra},"success":true,"status":200}"""
  private fun body() = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject

  @Test
  fun `a right password answers a challenge and no session, and the code finishes the sign-in with the keys opened`() = runTest {
    build()
    server.queue(MockResponse().setBody(challenge))
    val first = repo.login("carol", "carolpass")
    assertTrue((first as ApiResult.Failure).error is AppError.TwoFactorNeeded)
    assertNull("no session until the code", store.flow.value)
    body() // the password step

    server.queue(MockResponse().setBody(session()))
    assertTrue(repo.completeTwoFactor("123 456", recovery = false) is ApiResult.Success)
    val sent = server.next()
    assertEquals("/auth/login/two-factor", sent.path)
    assertNull("the second step is public: the challenge is its credential", sent.getHeader("Authorization"))
    val step = json.parseToJsonElement(sent.body.readUtf8()).jsonObject
    assertEquals("CH-1", step["challenge"]?.jsonPrimitive?.content); assertEquals("123456", step["code"]?.jsonPrimitive?.content)
    assertEquals("jwt-1", store.flow.value?.token)
    assertEquals("the wrap key kept from the password step opens the private key", "PUB-CAROL", String(vault.keyPair(7)!!.publicKey))
  }

  @Test
  fun `a wrong code may be tried again, a recovery code goes as typed, and an expired challenge sends the person back to the password`() = runTest {
    build()
    server.queue(MockResponse().setBody(challenge)); repo.login("carol", "carolpass"); body()
    server.queue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errors":["That code is not right."],"problems":[{"field":"code","code":"not_eligible"}]}"""))
    assertTrue((repo.completeTwoFactor("000000", recovery = false) as ApiResult.Failure).error is AppError.Validation)
    server.next()
    server.queue(MockResponse().setBody(session()))
    assertTrue("the challenge was still good", repo.completeTwoFactor("abcde-12345", recovery = true) is ApiResult.Success)
    assertEquals("abcde-12345", json.parseToJsonElement(server.next().body.readUtf8()).jsonObject["recoveryCode"]?.jsonPrimitive?.content)

    store.clear()
    server.queue(MockResponse().setBody(challenge)); repo.login("carol", "carolpass"); body()
    server.queue(MockResponse().setResponseCode(401).setBody("""{"success":false,"info":"Sign in again.","condition":"challenge_expired"}"""))
    assertTrue((repo.completeTwoFactor("123456", recovery = false) as ApiResult.Failure).error is AppError.Unauthorized)
    server.next()
    assertTrue("nothing left to finish", (repo.completeTwoFactor("123456", recovery = false) as ApiResult.Failure).error is AppError.Unauthorized)
  }

  @Test
  fun `required and not set up, the session comes with the requirement, and a refusal mid-session raises it too`() = runTest {
    build(EncryptionMode.SERVER)
    server.queue(MockResponse().setBody(session(""","twoFactor":{"setupRequired":true,"because":["group"]}""")))
    assertTrue(repo.login("carol", "carolpass") is ApiResult.Success)
    assertEquals(listOf("group"), repo.twoFactorSetupRequired.value)
    repo.twoFactorSetUp(); assertNull(repo.twoFactorSetupRequired.value)
    server.next()

    // Switched on while signed in: any request is refused so, and the app hears of it.
    server.queue(MockResponse().setResponseCode(403).setBody("""{"success":false,"info":"Set up two-factor sign-in first.","code":"two_factor_required.setup_required","status":403}"""))
    val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient.Builder().addInterceptor(SessionInterceptor(cache)).build()).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    runCatching { api.twoFactor() }
    assertEquals(emptyList<String>(), repo.twoFactorSetupRequired.value)
  }
}

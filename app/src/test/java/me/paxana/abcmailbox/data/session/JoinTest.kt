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
import me.paxana.abcmailbox.apiRequestCount
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

/**
 * Joining with an invite code (API PR #116): a new account whose keys are made on this phone, split wherever the
 * server knows the scheme, then the ordinary sign-in. The fake engine's see-through keys say what was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val vault = InMemoryVault()
  private val memory = FakeSchemeMemory()
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
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    return DefaultSessionRepository(store, api, SessionCache(), json, FixedMode(mode), FakeCryptoEngine(), vault, TestScope(UnconfinedTestDispatcher()), TestStrings(), memory).also { repo = it }
  }
  private val joined = """{"data":{"user":{"id":7,"username":"sam","role":"user","sponsoredBy":1},"chapter":{"id":1,"name":"Portland ABC"}},"success":true,"status":201}"""
  private fun login(keys: String) = """{"data":{"user":{"id":7,"username":"sam","role":"user"},"token":{"token":"jwt-1","expires":1},"keys":$keys},"success":true,"status":200}"""
  private val noKeys = """{"publicKey":null,"wrappedPrivateKey":null,"kdfSalt":null,"kdfParams":null,"hasRecovery":false,"orgKey":null}"""
  private fun splitKeys(password: String, salt: String = "SALT") = """{"publicKey":"PUB-NEW","wrappedPrivateKey":"wrapped(PUB-NEW)underwrap($password)with($salt)","kdfSalt":"$salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864},"hasRecovery":true,"orgKey":null}"""
  private fun body() = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
  private fun field(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]?.jsonPrimitive?.content

  @Test
  fun `the check says who is vouching and until when`() = runTest {
    build()
    server.queue(MockResponse().setBody("""{"data":{"chapter":{"id":1,"name":"Portland ABC"},"expiresAt":"2026-10-22T19:00:00.000Z"},"success":true,"status":200}"""))
    val r = repo.joinInfo("7Q4M2XKD9HBT") as ApiResult.Success
    assertEquals("Portland ABC", r.value.groupName); assertEquals("2026-10-22T19:00:00Z", r.value.expiresAt.toString())
    assertEquals("/auth/join?code=7Q4M2XKD9HBT", server.next().path)

    server.queue(MockResponse().setResponseCode(410).setBody("""{"success":false,"name":"InviteCodeError","info":"This invite code was already used. Ask the chapter for another.","status":410,"condition":"used"}"""))
    val gone = (repo.joinInfo("7Q4M2XKD9HBT") as ApiResult.Failure).error as AppError.Gone
    assertEquals("used", gone.condition)
  }

  @Test
  fun `end-to-end and split, the join carries a keypair made here under the auth key's salt, then signs in`() = runTest {
    build()
    server.queue(MockResponse().setBody(joined))
    server.queue(MockResponse().setBody(login(splitKeys("longenough1"))))
    val r = repo.join("7Q4M2XKD9HBT", " sam ", "longenough1", "", "Sam")
    assertTrue(r.toString(), r is ApiResult.Success)
    val sent = body()
    assertEquals("7Q4M2XKD9HBT", field(sent, "code")); assertEquals("sam", field(sent, "username")); assertEquals("Sam", field(sent, "name"))
    assertNull("an empty email is not sent", sent["email"])
    assertEquals("split", field(sent, "authScheme"))
    assertEquals("the auth key, never the password", "auth(longenough1)with(salt-1)", field(sent, "password"))
    assertEquals("PUB-NEW", field(sent, "publicKey"))
    assertEquals("wrapped(PUB-NEW)underwrap(longenough1)with(salt-1)", field(sent, "wrappedPrivateKey")); assertEquals("salt-1", field(sent, "kdfSalt"))
    assertEquals("wrapped(PUB-NEW)under(NEWCODE)", field(sent, "recoveryWrappedPrivateKey"))
    // Then the ordinary sign-in, which the code has no part in; the recovery code waits to be shown once.
    assertEquals("auth(longenough1)with(SALT)", field(body(), "password"))
    assertNotNull(vault.keyPair(7)); assertEquals("jwt-1", store.flow.value?.token)
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value); assertTrue(memory.isKnownSplit("sam"))
  }

  @Test
  fun `server mode and split, the join carries a salt and recipe and no keys, and an older API gets the password itself`() = runTest {
    build(EncryptionMode.SERVER)
    server.queue(MockResponse().setBody(joined)); server.queue(MockResponse().setBody(login(noKeys)))
    assertTrue(repo.join("7Q4M2XKD9HBT", "sam", "longenough1", "sam@example.org", null) is ApiResult.Success)
    val sent = body()
    assertEquals("split", field(sent, "authScheme")); assertEquals("auth(longenough1)with(salt-1)", field(sent, "password")); assertEquals("salt-1", field(sent, "kdfSalt"))
    assertNull(sent["publicKey"]); assertNull(sent["wrappedPrivateKey"]); assertEquals("sam@example.org", field(sent, "email"))
    assertNull("nothing to show: no keys were made", repo.pendingRecoveryCode.value)

    server.next()
    server.dispatcher = SchemeDispatcher() // 404: an API from before the scheme
    build()
    server.queue(MockResponse().setBody(joined)); server.queue(MockResponse().setBody(login("""{"publicKey":"PUB-NEW","wrappedPrivateKey":"wrapped(PUB-NEW)under(longenough1)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null}""")))
    // A different name: this phone now knows "sam" as split, and would refuse to send that name's password (the downgrade rule).
    assertTrue(repo.join("7Q4M2XKD9HBT", "dave", "longenough1", null, null) is ApiResult.Success)
    val plain = body()
    assertNull(plain["authScheme"]); assertEquals("longenough1", field(plain, "password")); assertEquals("wrapped(PUB-NEW)under(longenough1)", field(plain, "wrappedPrivateKey"))
  }

  @Test
  fun `a refused join sends nothing more and signs nobody in`() = runTest {
    build()
    server.queue(MockResponse().setResponseCode(400).setBody("""{"success":false,"name":"ValidationError","info":"Validation failed","errors":["Username is taken."],"status":400}"""))
    val r = repo.join("7Q4M2XKD9HBT", "sam", "longenough1", null, null) as ApiResult.Failure
    assertTrue(r.error is AppError.Validation); assertEquals(1, server.apiRequestCount)
    assertNull(store.flow.value); assertNull(vault.keyPair(7))
  }
}

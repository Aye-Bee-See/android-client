package me.paxana.abcmailbox.data.session

import me.paxana.abcmailbox.apiRequestCount
import me.paxana.abcmailbox.SchemeDispatcher
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.queue
import me.paxana.abcmailbox.text.TestStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
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

/** The end-to-end account flows against a fake server, with a see-through crypto engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionRepositoryE2eTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val engine = FakeCryptoEngine()
  private val vault = InMemoryVault()
  private val store = object : SessionStore {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow
    override suspend fun save(session: Session) { flow.value = session }
    override suspend fun clear() { flow.value = null }
  }
  private lateinit var repo: DefaultSessionRepository

  @Before
  fun setUp() {
    server.start()
    server.dispatcher = SchemeDispatcher() // an API from before the split scheme: everything below is the plain path
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    repo = DefaultSessionRepository(store, api, SessionCache(), json, FixedMode(EncryptionMode.E2E), engine, vault, TestScope(UnconfinedTestDispatcher()), TestStrings(), FakeSchemeMemory())
  }

  @After fun tearDown() = server.shutdown()

  private fun login(keys: String) = """{"data":{"user":{"id":7,"username":"carol","role":"user"},"token":{"token":"jwt-1","expires":1},"keys":$keys},"success":true,"status":200}"""
  private val noKeys = """{"publicKey":null,"wrappedPrivateKey":null,"kdfSalt":null,"kdfParams":null,"hasRecovery":false,"orgKey":null}"""
  private fun keysUnder(secret: String) = """{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under($secret)","kdfSalt":"salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864},"hasRecovery":true,"orgKey":null}"""
  private fun body(i: Int = 0) = json.parseToJsonElement(server.next().also { repeat(i) { } }.body.readUtf8()).jsonObject

  @Test
  fun `first sign-in on an end-to-end server makes keys and shows the code, and uploads all seven fields only once it is saved`() = runTest {
    server.queue(MockResponse().setBody(login(noKeys)))
    assertTrue(repo.login("carol", "carolpass") is ApiResult.Success)
    assertEquals("/auth/login", server.next().path)
    assertEquals("nothing goes up before the code is saved", 1, server.apiRequestCount)
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value)
    assertNull("locked until then", vault.keyPair(7))

    server.queue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    assertEquals(ApiResult.Success(0), repo.recoveryCodeSaved())
    val put = server.next()
    assertEquals("PUT", put.method); assertEquals("/auth/keys", put.path)
    val sent = json.parseToJsonElement(put.body.readUtf8()).jsonObject
    assertEquals(setOf("publicKey", "wrappedPrivateKey", "kdfSalt", "kdfParams", "recoveryWrappedPrivateKey", "recoverySalt", "recoveryKdfParams"), sent.keys)
    assertEquals("wrapped(PUB-NEW)under(carolpass)", sent["wrappedPrivateKey"]!!.jsonPrimitive.content)
    assertEquals("""{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}""", sent["kdfParams"].toString())
    assertNotNull(vault.keyPair(7))
    assertNull(repo.pendingRecoveryCode.value)
  }

  @Test
  fun `letters the server sealed to the new key are counted, and a caughtUp of null counts as none`() = runTest {
    server.queue(MockResponse().setBody(login(noKeys)))
    repo.login("carol", "carolpass")
    server.queue(MockResponse().setBody("""{"data":{"publicKey":"PUB-NEW","caughtUp":{"letters":4,"sealed":3,"dropped":3}},"success":true,"status":200}"""))
    assertEquals(ApiResult.Success(3), repo.recoveryCodeSaved())

    repo.logout()
    server.queue(MockResponse().setBody(login(noKeys)))
    repo.login("carol", "carolpass")
    server.queue(MockResponse().setBody("""{"data":{"publicKey":"PUB-NEW","caughtUp":null},"success":true,"status":200}"""))
    assertEquals(ApiResult.Success(0), repo.recoveryCodeSaved())
  }

  @Test
  fun `an upload that fails keeps the same code and keys, and the next try sends the very same fields`() = runTest {
    server.queue(MockResponse().setBody(login(noKeys)))
    repo.login("carol", "carolpass"); server.next()
    server.queue(MockResponse().setResponseCode(503).setBody("""{"success":false,"name":"ServerError","info":"Starting","status":503}"""))
    assertTrue(repo.recoveryCodeSaved() is ApiResult.Failure)
    val first = server.next().body.readUtf8()
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value)
    assertNull(vault.keyPair(7))

    server.queue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    assertTrue(repo.recoveryCodeSaved() is ApiResult.Success)
    assertEquals(first, server.next().body.readUtf8())
    assertNotNull(vault.keyPair(7))
  }

  @Test
  fun `a key set by another device first forgets this code and leaves the account to unlock with its password`() = runTest {
    server.queue(MockResponse().setBody(login(noKeys)))
    repo.login("carol", "carolpass")
    server.queue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"KeyChangeError","info":"The public key is already set and cannot change; envelopes are sealed to it.","status":409}"""))
    val r = repo.recoveryCodeSaved() as ApiResult.Failure
    assertTrue(r.error is AppError.Conflict)
    assertNull(repo.pendingRecoveryCode.value)
    assertNull(vault.keyPair(7))
    assertTrue(repo.keysLocked.value)
  }

  @Test
  fun `unlocking an account that never got its key signs in again and makes the key and a code, as a first sign-in does`() = runTest {
    // The app was stopped on the code screen: signed in, nothing uploaded, nothing on the phone.
    server.queue(MockResponse().setBody(login(noKeys)))
    repo.login("carol", "carolpass"); server.next()

    // A wrong password is refused by the server, and said as this prompt says one.
    server.queue(MockResponse().setBody("""{"data":$noKeys,"success":true,"status":200}"""))
    server.queue(MockResponse().setResponseCode(401).setBody("""{"success":false,"name":"AuthenticationError","info":"Unauthorized","status":401}"""))
    val refused = repo.unlock("not-it") as ApiResult.Failure
    assertEquals("That password does not open your letters.", refused.error.userMessage)
    server.next(); server.next()

    server.queue(MockResponse().setBody("""{"data":$noKeys,"success":true,"status":200}"""))
    server.queue(MockResponse().setBody(login(noKeys)))
    assertTrue(repo.unlock("carolpass") is ApiResult.Success)
    assertEquals("/auth/keys", server.next().path)
    assertEquals("/auth/login", server.next().path)
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value)
  }

  @Test
  fun `sign-in with existing keys unlocks them with the password and shows no code`() = runTest {
    server.queue(MockResponse().setBody(login(keysUnder("carolpass"))))
    repo.login("carol", "carolpass")
    assertEquals("PUB-CAROL", String(vault.keyPair(7)!!.publicKey))
    assertNull(repo.pendingRecoveryCode.value)
    assertEquals(1, server.apiRequestCount)
  }

  @Test
  fun `claiming opens the key with the token and re-wraps the same key under the new password`() = runTest {
    server.queue(MockResponse().setBody("""{"data":{"writer":{"id":7,"name":"Carol"},"chapter":{"id":1,"name":"Test Chapter"},"expiresAt":"2026-09-20T00:00:00.000Z","publicKey":"PUB-CAROL","claimWrappedPrivateKey":"wrapped(PUB-CAROL)under(TOKEN24)","claimSalt":"s","claimKdfParams":{"kdf":"argon2id"}},"success":true,"status":200}"""))
    val info = (repo.claimInfo("TOKEN24") as ApiResult.Success).value
    assertTrue(info.endToEnd)

    server.queue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    server.queue(MockResponse().setBody(login(keysUnder("newpass77"))))
    assertTrue(repo.claim("TOKEN24", "carol", "newpass77", null) is ApiResult.Success)

    server.next() // the check
    val claim = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
    assertEquals("wrapped(PUB-CAROL)under(newpass77)", claim["wrappedPrivateKey"]!!.jsonPrimitive.content)
    assertEquals("wrapped(PUB-CAROL)under(REWRAPCODE)", claim["recoveryWrappedPrivateKey"]!!.jsonPrimitive.content)
    assertNull("the public key never changes, so it is not sent", claim["publicKey"])
    assertEquals("REWRAPCODE", repo.pendingRecoveryCode.value)
    assertEquals("PUB-CAROL", String(vault.keyPair(7)!!.publicKey))
  }

  @Test
  fun `a password change carries the key re-wrapped under the new password`() = runTest {
    server.queue(MockResponse().setBody(login(keysUnder("carolpass"))))
    repo.login("carol", "carolpass")
    server.queue(MockResponse().setBody(login(keysUnder("carolpass")))) // the current-password check
    server.queue(MockResponse().setBody("""{"data":{"updatedRows":[1],"token":{"token":"jwt-2","expires":2}},"success":true,"status":200}"""))
    assertTrue(repo.changePassword("carolpass", "brandnew77") is ApiResult.Success)

    server.next(); server.next()
    val put = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
    assertEquals("brandnew77", put["password"]!!.jsonPrimitive.content)
    assertEquals("wrapped(PUB-CAROL)under(brandnew77)", put["wrappedPrivateKey"]!!.jsonPrimitive.content)
    assertEquals("jwt-2", store.flow.value?.token)
  }

  @Test
  fun `recovery opens the key with the code, answers the challenge, and signs in`() = runTest {
    server.queue(MockResponse().setBody("""{"data":{"publicKey":"PUB-CAROL","recoveryWrappedPrivateKey":"wrapped(PUB-CAROL)under(SAVEDCODE)","recoverySalt":"s","recoveryKdfParams":{"kdf":"argon2id"},"sealedChallenge":"CH"},"success":true,"status":200}"""))
    server.queue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    server.queue(MockResponse().setBody(login(keysUnder("afterrecovery"))))
    assertTrue(repo.recover("carol", "SAVEDCODE", "afterrecovery") is ApiResult.Success)

    assertEquals("/auth/recover?username=carol", server.next().path)
    val finish = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
    assertEquals("opened(CH)by(PUB-CAROL)", finish["challenge"]!!.jsonPrimitive.content)
    assertEquals("wrapped(PUB-CAROL)under(afterrecovery)", finish["wrappedPrivateKey"]!!.jsonPrimitive.content)
    assertNotNull(vault.keyPair(7))
  }

  @Test
  fun `a wrong recovery code is refused locally and never reaches the finish step`() = runTest {
    server.queue(MockResponse().setBody("""{"data":{"publicKey":"PUB-CAROL","recoveryWrappedPrivateKey":"wrapped(PUB-CAROL)under(SAVEDCODE)","recoverySalt":"s","recoveryKdfParams":{"kdf":"argon2id"},"sealedChallenge":"CH"},"success":true,"status":200}"""))
    val r = repo.recover("carol", "WRONGCODE", "afterrecovery") as ApiResult.Failure
    assertTrue((r.error as AppError.Validation).errors.single().contains("does not match"))
    assertEquals(1, server.apiRequestCount)
  }

  @Test
  fun `signing out forgets the key`() = runTest {
    server.queue(MockResponse().setBody(login(keysUnder("carolpass"))))
    repo.login("carol", "carolpass")
    server.queue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    repo.logout()
    assertNull(vault.keyPair(7))
  }
}

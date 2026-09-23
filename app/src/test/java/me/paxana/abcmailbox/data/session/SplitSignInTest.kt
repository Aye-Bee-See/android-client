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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The split scheme (API PR #114): the password never reaches the server. The fake engine's "auth key" for a
 * password and salt is `auth(<password>)with(<salt>)`, so every assertion can read what was sent and see that it
 * was never the password.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitSignInTest {
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
  private fun login(keys: String) = """{"data":{"user":{"id":7,"username":"carol","role":"user"},"token":{"token":"jwt-1","expires":1},"keys":$keys},"success":true,"status":200}"""
  private val noKeys = """{"publicKey":null,"wrappedPrivateKey":null,"kdfSalt":null,"kdfParams":null,"hasRecovery":false,"orgKey":null}"""
  /** Keys as a split account stores them: wrapped under the wrap key of `password` with the account's salt. */
  private fun splitKeys(password: String, salt: String = "SALT") = """{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)underwrap($password)with($salt)","kdfSalt":"$salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864},"hasRecovery":true,"orgKey":null}"""
  private fun body() = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
  private fun field(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]?.jsonPrimitive?.content

  @Test
  fun `signing in sends the auth key, never the password, and the wrap key opens the private key`() = runTest {
    build()
    server.queue(MockResponse().setBody(login(splitKeys("carolpass"))))
    assertTrue(repo.login("carol", "carolpass") is ApiResult.Success)
    val sent = body()
    assertEquals("auth(carolpass)with(SALT)", field(sent, "password"))
    assertFalse(sent.toString().contains("carolpass)\"") && field(sent, "password") == "carolpass")
    assertEquals("PUB-CAROL", String(vault.keyPair(7)!!.publicKey))
    assertTrue("this phone now knows carol is split", memory.isKnownSplit("Carol "))
    assertEquals(1, server.apiRequestCount)
  }

  @Test
  fun `a split account with no keys yet gets them wrapped under the sign-in's own salt, so one derivation opens both`() = runTest {
    build()
    server.queue(MockResponse().setBody(login(noKeys)))
    server.queue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    repo.login("carol", "carolpass")
    server.next()
    val put = server.next(); assertEquals("/auth/keys", put.path)
    val sent = json.parseToJsonElement(put.body.readUtf8()).jsonObject
    assertEquals("wrapped(PUB-NEW)underwrap(carolpass)with(SALT)", field(sent, "wrappedPrivateKey"))
    assertEquals("the same salt the auth key came from", "SALT", field(sent, "kdfSalt"))
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value)
  }

  @Test
  fun `a phone that knows an account is split refuses to send its password, whatever the server says`() = runTest {
    build(); memory.split += "carol"
    server.dispatcher = SchemeDispatcher(SchemeDispatcher.plain())
    val r = repo.login("carol", "carolpass") as ApiResult.Failure
    assertTrue(r.error is AppError.Forbidden); assertTrue(r.error.userMessage!!.contains("carol"))
    assertEquals("nothing was sent", 0, server.apiRequestCount)
    assertNull(store.flow.value)
  }

  @Test
  fun `an account from before the scheme, and an older server, still sign in with the password itself`() = runTest {
    build()
    server.dispatcher = SchemeDispatcher(SchemeDispatcher.plain())
    server.queue(MockResponse().setBody(login("""{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under(carolpass)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null}""")))
    assertTrue(repo.login("carol", "carolpass") is ApiResult.Success)
    assertEquals("carolpass", field(body(), "password")); assertNotNull(vault.keyPair(7)); assertFalse(memory.isKnownSplit("carol"))

    server.dispatcher = SchemeDispatcher() // 404: no such address
    server.queue(MockResponse().setBody(login(noKeys))); server.queue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    assertTrue(repo.login("dave", "davepass99") is ApiResult.Success)
    assertEquals("davepass99", field(body(), "password"))
  }

  @Test
  fun `under the flag every name is called split, so an account from before gets one more try with the password, and a known split account gets none`() = runTest {
    build()
    val refused = MockResponse().setResponseCode(401).setBody("""{"success":false,"name":"AuthenticationError","info":"Unauthorized","status":401}""")
    server.queue(refused)
    server.queue(MockResponse().setBody(login("""{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under(carolpass)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null}""")))
    assertTrue(repo.login("carol", "carolpass") is ApiResult.Success)
    assertEquals("auth(carolpass)with(SALT)", field(body(), "password"))
    assertEquals("the password itself, once", "carolpass", field(body(), "password"))
    assertNotNull("and it opened the keys the old way", vault.keyPair(7)); assertFalse("a plain sign-in is not remembered as split", memory.isKnownSplit("carol"))

    memory.split += "dave"
    server.queue(refused)
    val r = repo.login("dave", "davepass99") as ApiResult.Failure
    assertTrue(r.error is AppError.Unauthorized)
    assertEquals("auth(davepass99)with(SALT)", field(body(), "password"))
    assertEquals("no second try: the password stays on the phone", 3, server.apiRequestCount)
  }

  @Test
  fun `a handshake that is neither a proper plain nor a proper split answer sends nothing at all`() = runTest {
    build()
    val salted = """{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}"""
    for (answer in listOf(
      """{"data":{"scheme":"split"},"success":true,"status":200}""", // split without its salt
      """{"data":{"scheme":"split","kdfSalt":"SALT"},"success":true,"status":200}""", // or without its recipe
      """{"data":{"scheme":"pake2","kdfSalt":"SALT","kdfParams":$salted},"success":true,"status":200}""", // a scheme this app does not know
      """{"data":{},"success":true,"status":200}""", // nothing
    )) {
      server.dispatcher = SchemeDispatcher(MockResponse().setBody(answer))
      val r = repo.login("carol", "carolpass") as ApiResult.Failure
      assertEquals(answer, "The server would not accept this password in the form the app sends it. The app may need updating.", r.error.userMessage)
    }
    // Every request the server saw was a handshake (each answer had its own dispatcher, so apiRequestCount cannot say).
    val seen = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
    assertEquals(4, seen.size); assertTrue("the password went out for none of them: $seen", seen.all { it.startsWith("/auth/login-params") })
  }

  @Test
  fun `unlocking the keys after a restart follows the same rules as signing in, and sends no password`() = runTest {
    build()
    store.save(Session("jwt-1", 0L, SessionUser(7, "carol", null, null, "user", null)))
    // An account from before, under the flag: the handshake says split, the key is wrapped under the password itself.
    val plainKeys = """{"data":{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under(carolpass)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null},"success":true,"status":200}"""
    server.queue(MockResponse().setBody(plainKeys))
    assertTrue(repo.unlock("carolpass") is ApiResult.Success); assertNotNull(vault.keyPair(7))
    assertEquals("only the key bundle was fetched", 1, server.apiRequestCount)

    // A split account: its wrap key opens the key, and a wrong password opens nothing and is tried no other way.
    vault.clear(); memory.split += "carol"
    server.queue(MockResponse().setBody("""{"data":${splitKeys("carolpass")},"success":true,"status":200}"""))
    assertTrue(repo.unlock("carolpass") is ApiResult.Success); assertNotNull(vault.keyPair(7))
    vault.clear()
    server.queue(MockResponse().setBody("""{"data":${splitKeys("carolpass")},"success":true,"status":200}"""))
    assertTrue(repo.unlock("not-it") is ApiResult.Failure); assertNull(vault.keyPair(7))
  }

  @Test
  fun `claiming an account makes it split, with the key re-wrapped beside the auth key`() = runTest {
    build()
    server.queue(MockResponse().setBody("""{"data":{"writer":{"id":7,"name":"Carol"},"chapter":{"id":1,"name":"Test Chapter"},"expiresAt":"2026-10-05T00:00:00.000Z","publicKey":"PUB-CAROL","claimWrappedPrivateKey":"wrapped(PUB-CAROL)under(TOKEN24)","claimSalt":"cs","claimKdfParams":{"kdf":"argon2id"}},"success":true,"status":200}"""))
    repo.claimInfo("TOKEN24"); server.next()
    // After the claim, the handshake answers with the salt the claim chose (the fake engine's first salt is "salt-1").
    server.dispatcher = SchemeDispatcher(SchemeDispatcher.split("salt-1"))
    server.queue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    server.queue(MockResponse().setBody(login(splitKeys("newpass7777", "salt-1"))))
    assertTrue(repo.claim("TOKEN24", "carol", "newpass7777", null) is ApiResult.Success)
    val claim = body()
    assertEquals("split", field(claim, "authScheme"))
    assertEquals("auth(newpass7777)with(salt-1)", field(claim, "password"))
    assertEquals("wrapped(PUB-CAROL)underwrap(newpass7777)with(salt-1)", field(claim, "wrappedPrivateKey")); assertEquals("salt-1", field(claim, "kdfSalt"))
    assertEquals("wrapped(PUB-CAROL)under(REWRAPCODE)", field(claim, "recoveryWrappedPrivateKey"))
    assertEquals("and the sign-in that follows uses the auth key too", "auth(newpass7777)with(salt-1)", field(body(), "password"))
    assertEquals("PUB-CAROL", String(vault.keyPair(7)!!.publicKey)); assertTrue(memory.isKnownSplit("carol"))
  }

  @Test
  fun `on a server-mode API a claim is split too, with a salt and recipe and no keys`() = runTest {
    build(EncryptionMode.SERVER)
    server.queue(MockResponse().setBody("""{"data":{"writer":{"id":7,"name":"Carol"},"chapter":null,"expiresAt":null},"success":true,"status":200}"""))
    repo.claimInfo("TOKEN24"); server.next()
    server.queue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    server.queue(MockResponse().setBody(login(noKeys)))
    assertTrue(repo.claim("TOKEN24", "carol", "newpass7777", null) is ApiResult.Success)
    val claim = body()
    assertEquals("split", field(claim, "authScheme")); assertEquals("auth(newpass7777)with(salt-1)", field(claim, "password"))
    assertEquals("salt-1", field(claim, "kdfSalt")); assertNotNull(claim["kdfParams"]); assertNull(claim["wrappedPrivateKey"])
  }

  @Test
  fun `a password change moves an account to split, re-wrapping the key, and the phone remembers`() = runTest {
    build()
    server.dispatcher = SchemeDispatcher(SchemeDispatcher.plain()) // an account from before
    server.queue(MockResponse().setBody(login("""{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under(carolpass)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null}""")))
    repo.login("carol", "carolpass"); server.next()
    server.queue(MockResponse().setBody(login("""{"publicKey":"PUB-CAROL","wrappedPrivateKey":"wrapped(PUB-CAROL)under(carolpass)","kdfSalt":"s","kdfParams":{"kdf":"argon2id"},"hasRecovery":true,"orgKey":null}"""))) // the current-password check
    server.queue(MockResponse().setBody("""{"data":{"updatedRows":[1],"token":{"token":"jwt-2","expires":2}},"success":true,"status":200}"""))
    assertTrue(repo.changePassword("carolpass", "brandnew7777") is ApiResult.Success)
    server.next()
    val put = body()
    assertEquals("split", field(put, "authScheme")); assertEquals("auth(brandnew7777)with(salt-1)", field(put, "password"))
    assertEquals("wrapped(PUB-CAROL)underwrap(brandnew7777)with(salt-1)", field(put, "wrappedPrivateKey"))
    assertTrue(memory.isKnownSplit("carol")); assertEquals("jwt-2", store.flow.value?.token)
  }

  @Test
  fun `recovery sets a split password, and deleting an account proves the auth key, not the password`() = runTest {
    build()
    server.dispatcher = SchemeDispatcher(SchemeDispatcher.split("salt-1")) // the salt recovery will choose (the fake engine's first)
    server.queue(MockResponse().setBody("""{"data":{"publicKey":"PUB-CAROL","recoveryWrappedPrivateKey":"wrapped(PUB-CAROL)under(SAVEDCODE)","recoverySalt":"s","recoveryKdfParams":{"kdf":"argon2id"},"sealedChallenge":"CH"},"success":true,"status":200}"""))
    server.queue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    server.queue(MockResponse().setBody(login(splitKeys("afterrecovery7", "salt-1"))))
    assertTrue(repo.recover("carol", "SAVEDCODE", "afterrecovery7") is ApiResult.Success)
    server.next()
    val finish = body()
    assertEquals("split", field(finish, "authScheme")); assertEquals("auth(afterrecovery7)with(salt-1)", field(finish, "password"))
    assertEquals("wrapped(PUB-CAROL)underwrap(afterrecovery7)with(salt-1)", field(finish, "wrappedPrivateKey"))
    server.next() // the sign-in

    server.queue(MockResponse().setBody(login(splitKeys("afterrecovery7", "salt-1"))))
    server.queue(MockResponse().setBody("""{"data":{"deleted":1,"letters":0,"replies":0,"attachments":0,"threads":0},"success":true,"status":200}"""))
    assertTrue(repo.deleteAccount("afterrecovery7") is ApiResult.Success)
    assertEquals("auth(afterrecovery7)with(salt-1)", field(body(), "password"))
    assertEquals("""{"id":7,"password":"auth(afterrecovery7)with(salt-1)"}""", server.next().body.readUtf8())
  }
}

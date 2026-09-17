package me.paxana.abcmailbox.data.crypto

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
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

class GroupKeyringTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val engine = FakeCryptoEngine()
  private val vault = InMemoryVault()
  private val sessions = FakeSessionRepository()
  private val member = engine.keyPairFor("PUB-MEMBER")
  private val groupPublic get() = engine.publicText(engine.keyPairFor("PUB-GROUP"))
  private lateinit var retrofit: Retrofit

  @Before
  fun setUp() = runTest {
    server.start()
    retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    sessions.signInAs(SessionUser(9, "member1", "Sam", null, "chapter", 1))
    vault.store(9, member)
  }

  @After fun tearDown() = server.shutdown()

  // The unconfined scope runs the keyring's sign-out watcher eagerly, which is the ordering a real launch can also produce.
  private fun keyring(mode: EncryptionMode = EncryptionMode.E2E) =
    DefaultGroupKeyring(FixedMode(mode), sessions, vault, engine, retrofit.create(AuthApi::class.java), retrofit.create(GroupApi::class.java), json, TestScope(UnconfinedTestDispatcher()))

  private fun bundle(groupPublicKey: String?, sealed: String?) =
    MockResponse().setBody("""{"data":{"publicKey":"x","orgKey":{"chapterId":1,"chapterName":"Test Chapter","chapterPublicKey":${groupPublicKey?.let { "\"$it\"" }},"keyVersion":3,"wrappedOrgPrivateKey":${sealed?.let { "\"$it\"" }}}},"success":true,"status":200}""")

  @Test
  fun `server mode and writers need no group key and ask the server nothing`() = runTest {
    assertEquals(GroupKeyState.NotNeeded, keyring(EncryptionMode.SERVER).load())
    sessions.signInAs(SessionUser(1, "user1", null, null, "user", null))
    assertEquals(GroupKeyState.NotNeeded, keyring().load())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `a group with no key, and a member not yet handed it, are told apart`() = runTest {
    server.enqueue(bundle(null, null))
    assertEquals(GroupKeyState.NotSetUp(1), keyring().load())
    server.enqueue(bundle(groupPublic, null))
    assertEquals(GroupKeyState.NotHeld(1), keyring().load())
  }

  @Test
  fun `a key sealed to a keypair this member no longer has counts as not held`() = runTest {
    server.enqueue(bundle(groupPublic, engine.sealPrivateKey("private-of-PUB-GROUP".toByteArray(), "someone-elses-public-key")))
    assertEquals(GroupKeyState.NotHeld(1), keyring().load())
  }

  @Test
  fun `a locked device opens nothing and asks the server nothing`() = runTest {
    vault.clear()
    assertEquals(GroupKeyState.Locked, keyring().load())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `the group key opens with the member's key, custody keys open with the group's, and both are loaded once`() = runTest {
    val writerPublic = engine.publicText(engine.keyPairFor("PUB-ALEX"))
    server.enqueue(bundle(groupPublic, engine.sealPrivateKey("private-of-PUB-GROUP".toByteArray(), engine.publicText(member))))
    server.enqueue(MockResponse().setBody("""{"data":[
      {"id":44,"name":"Alex","managedBy":1,"publicKey":"$writerPublic","orgWrappedPrivateKey":"${engine.sealPrivateKey("private-of-PUB-ALEX".toByteArray(), groupPublic)}"},
      {"id":45,"name":"From before encryption","managedBy":1},
      {"id":46,"name":"Sealed to an old group key","managedBy":1,"publicKey":"$writerPublic","orgWrappedPrivateKey":"${engine.sealPrivateKey("private-of-PUB-ALEX".toByteArray(), "old-group-key")}"}],"success":true,"status":200}"""))
    val ring = keyring()
    val ready = ring.load() as GroupKeyState.Ready
    assertEquals(3, ready.key.version); assertEquals(1, ready.key.groupId); assertEquals(groupPublic, ready.key.publicKey)
    assertEquals("private-of-PUB-ALEX", String(ring.writerKey(44)!!.privateKey))
    assertNull(ring.writerKey(45)); assertNull("one unreadable writer must not spoil the rest", ring.writerKey(46))

    ring.load(); ring.load()
    assertEquals("loaded once per sign-in", 2, server.requestCount)
  }

  @Test
  fun `signing out wipes the keys at once`() = runTest {
    server.enqueue(bundle(groupPublic, engine.sealPrivateKey("private-of-PUB-GROUP".toByteArray(), engine.publicText(member))))
    server.enqueue(MockResponse().setBody("""{"data":[],"success":true,"status":200}"""))
    val ring = keyring()
    val key = (ring.load() as GroupKeyState.Ready).key
    assertNotNull(ring.groupKey())
    sessions.logout()
    assertNull(ring.groupKey())
    assertEquals(GroupKeyState.NotNeeded, ring.state.value)
    assertTrue("the private key is zeroed, not just dropped", key.keyPair.privateKey.all { it == 0.toByte() })
  }
}

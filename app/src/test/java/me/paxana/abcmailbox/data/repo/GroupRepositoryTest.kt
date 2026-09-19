package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.GroupApi
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.crypto.FakeKeyring
import me.paxana.abcmailbox.data.crypto.GroupKey
import me.paxana.abcmailbox.data.crypto.GroupKeyState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.Verification
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.letters.ComposeViewModelTest
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

class GroupRepositoryTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private lateinit var repo: DefaultGroupRepository
  private val engine = FakeCryptoEngine()
  private val vault = InMemoryVault()
  private val keyring = FakeKeyring()
  private val sessions = FakeSessionRepository()
  private val groupPair get() = engine.keyPairFor("PUB-GROUP")
  private val groupPublic get() = engine.publicText(groupPair)

  private fun build(mode: EncryptionMode): DefaultGroupRepository {
    val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    val authApi = retrofit.create(AuthApi::class.java)
    val codec = LetterCodec(FixedMode(mode), engine, vault, sessions, authApi, json, keyring, TestStrings())
    return DefaultGroupRepository(retrofit.create(GroupApi::class.java), ComposeViewModelTest.FakeLetters(), NoDirectory, codec, json, keyring, engine, vault, sessions, authApi, retrofit.create(LettersApi::class.java), TestStrings())
  }

  /** A member of group 1 who holds its key (version 3), on an end-to-end server. */
  private fun endToEnd(): DefaultGroupRepository {
    sessions.signInAs(SessionUser(9, "member1", "Sam", null, "chapter", 1))
    keyring.state.value = GroupKeyState.Ready(GroupKey(1, groupPair, groupPublic, 3))
    return build(EncryptionMode.E2E)
  }

  @Before
  fun setUp() {
    server.start()
    repo = build(EncryptionMode.SERVER)
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun `a status move sends id and status, and a refusal surfaces the API's own sentence`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"id":41,"chat":41,"sender":"user","prisoner":1,"user":4,"status":"printed","relayChapter":1,"messageText":"Hi"},"success":true,"status":200}"""))
    val ok = repo.setStatus(41, LetterStatus.PRINTED) as ApiResult.Success
    assertEquals(LetterStatus.PRINTED, ok.value.status)
    val req = server.next()
    assertEquals("PUT", req.method); assertEquals("/messaging/status", req.path)
    assertEquals("""{"id":41,"status":"printed"}""", req.body.readUtf8())

    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"LetterStatusError","info":"Error updating letter status.","status":409,"error":"A printed letter cannot move to queued."}"""))
    val refused = repo.setStatus(41, LetterStatus.QUEUED) as ApiResult.Failure
    assertEquals("A printed letter cannot move to queued.", refused.error.userMessage)
  }

  @Test
  fun `writers hide the placeholder address and know whether a token is live`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":[
      {"id":44,"name":"Zed","username":"writer-1","email":"writer-1@managed.example","managedBy":1,"managerNote":"Tuesdays","claimToken":{"expiresAt":"2999-01-01T00:00:00.000Z"}},
      {"id":45,"name":"alex","username":"writer-2","email":"alex@riseup.net","managedBy":1,"claimToken":null},
      {"id":46,"name":"Old","username":"writer-3","managedBy":1,"claimToken":{"expiresAt":"2001-01-01T00:00:00.000Z"}},
      {"id":48,"name":"Anonymous writer","username":"anon-1","managedBy":1,"anonymousForChapter":1,"claimToken":null}],"success":true,"status":200}"""))
    val writers = (repo.writers() as ApiResult.Success).value
    // Sorted without regard to case; the group's shared anonymous account is not a person to hand off.
    assertEquals(listOf("alex", "Old", "Zed"), writers.map { it.name })
    val zed = writers.last()
    assertNull(zed.email); assertEquals("Tuesdays", zed.note); assertTrue(zed.hasLiveToken)
    assertEquals("alex@riseup.net", writers.first().email); assertFalse(writers.first().hasLiveToken)
    assertFalse("an expired token is not live", writers[1].hasLiveToken)
  }

  @Test
  fun `adding a writer trims and omits blanks, and a token is returned once`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":47,"name":"Maria T.","managedBy":1},"success":true,"status":201}"""))
    repo.addWriter("  Maria T. ", "  ", "")
    assertEquals("""{"name":"Maria T."}""", server.next().body.readUtf8())

    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"writer":47,"token":"R60GRVGC3V007NS41T6ZAPXG","expiresAt":"2026-09-20T22:20:47.692Z"},"success":true,"status":201}"""))
    val t = (repo.issueToken(47) as ApiResult.Success).value
    assertEquals("R60GRVGC3V007NS41T6ZAPXG", t.token)
    assertEquals("""{"writer":47}""", server.next().body.readUtf8())

    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    assertTrue(repo.revokeToken(47) is ApiResult.Success)
    val revoke = server.next()
    assertEquals("DELETE", revoke.method); assertEquals("""{"writer":47}""", revoke.body.readUtf8())
  }

  @Test
  fun `a group whose account is not active sees the API's explanation`() = runTest {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"info":"Your group is pending approval by a network admin.","status":403}"""))
    val r = repo.writers() as ApiResult.Failure
    assertEquals(AppError.Forbidden("Your group is pending approval by a network admin."), r.error)
  }

  // End-to-end ------------------------------------------------------------------------------------

  @Test
  fun `an end-to-end writer gets a keypair made here, sealed to the group key of the version this device holds`() = runTest {
    val e2e = endToEnd(); engine.nextNewKey = "PUB-MARIA"
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":47,"name":"Maria T.","managedBy":1},"success":true,"status":201}"""))
    e2e.addWriter("Maria T.", null, null)
    val maria = engine.publicText(engine.keyPairFor("PUB-MARIA"))
    assertEquals("""{"name":"Maria T.","publicKey":"$maria","orgWrappedPrivateKey":"sealedkey(private-of-PUB-MARIA)to($groupPublic)","orgKeyVersion":3}""", server.next().body.readUtf8())
    assertEquals("usable at once, without reloading the keyring", "private-of-PUB-MARIA", String(keyring.writerKey(47)!!.privateKey))
  }

  @Test
  fun `a rotated group key is opened again and the writer sealed to the new one, once`() = runTest {
    val e2e = endToEnd()
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"KeyVersionError","error":"The group key was rotated.","status":409}"""))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":47,"name":"Maria T.","managedBy":1},"success":true,"status":201}"""))
    assertTrue(e2e.addWriter("Maria T.", null, null) is ApiResult.Success)
    assertEquals(1, keyring.forced); assertEquals(2, server.requestCount)
  }

  @Test
  fun `an end-to-end claim token never reaches the server, only its hash and the key wrapped under it`() = runTest {
    val e2e = endToEnd(); keyring.custody[44] = engine.keyPairFor("PUB-ALEX"); engine.nextToken = "R60GRVGC3V007NS41T6ZAPXG"
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"writer":44,"expiresAt":"2026-09-20T22:20:47.692Z"},"success":true,"status":201}"""))
    val issued = (e2e.issueToken(44) as ApiResult.Success).value
    assertEquals("R60GRVGC3V007NS41T6ZAPXG", issued.token); assertNotNull(issued.expiresAt)
    val sent = server.next().body.readUtf8()
    assertFalse("the token itself must not be in the request", sent.contains("R60GRVGC3V007NS41T6ZAPXG\"") && !sent.contains("hash("))
    assertEquals("""{"writer":44,"tokenHash":"hash(R60GRVGC3V007NS41T6ZAPXG)","claimWrappedPrivateKey":"wrapped(private-of-PUB-ALEX)under(R60GRVGC3V007NS41T6ZAPXG)","claimSalt":"claim-salt","claimKdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}}""", sent)
  }

  @Test
  fun `a writer from before encryption is given a keypair the first time a token is made for them`() = runTest {
    val e2e = endToEnd(); engine.nextNewKey = "PUB-OLD"
    server.enqueue(MockResponse().setBody("""{"data":[{"id":45,"name":"Old","managedBy":1}],"success":true,"status":200}"""))
    server.enqueue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"writer":45,"expiresAt":null},"success":true,"status":201}"""))
    assertTrue(e2e.issueToken(45) is ApiResult.Success)
    assertEquals("/auth/writers?page_size=100", server.next().path)
    val put = server.next()
    assertEquals("PUT", put.method); assertEquals("/auth/user", put.path)
    assertEquals("""{"id":45,"publicKey":"${engine.publicText(engine.keyPairFor("PUB-OLD"))}","orgWrappedPrivateKey":"sealedkey(private-of-PUB-OLD)to($groupPublic)","orgKeyVersion":3}""", put.body.readUtf8())
    assertTrue(server.next().body.readUtf8().contains("wrapped(private-of-PUB-OLD)"))
  }

  @Test
  fun `setting up the group key seals it to this member and then looks at the server's view again`() = runTest {
    sessions.signInAs(SessionUser(9, "member1", "Sam", null, "chapter", 1)); vault.store(9, engine.keyPairFor("PUB-MEMBER"))
    val e2e = build(EncryptionMode.E2E); engine.nextNewKey = "PUB-GROUP"
    server.enqueue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    assertTrue(e2e.setUpGroupKey() is ApiResult.Success)
    val member = engine.publicText(engine.keyPairFor("PUB-MEMBER"))
    assertEquals("""{"chapter":1,"publicKey":"$groupPublic","wrappedOrgPrivateKey":"sealedkey(private-of-PUB-GROUP)to($member)"}""", server.next().body.readUtf8())
    assertEquals(1, keyring.forced)
  }

  @Test
  fun `the key is handed to a member sealed to their public key, and not at all to one who has none`() = runTest {
    val e2e = endToEnd()
    val members = """{"data":{"chapter":1,"publicKey":"$groupPublic","keyVersion":3,"members":[{"id":9,"username":"member1","name":"Sam","publicKey":"PUB-SAM","holdsGroupKey":true},{"id":10,"username":"member2","name":"","publicKey":"PUB-NOOR","holdsGroupKey":false},{"id":11,"username":"member3","publicKey":null,"holdsGroupKey":false}]},"success":true,"status":200}"""
    server.enqueue(MockResponse().setBody(members))
    val listed = (e2e.members() as ApiResult.Success).value
    assertEquals(listOf("Sam", "member2", "member3"), listed.map { it.name })
    assertTrue(listed[0].isMe); assertFalse(listed[2].hasOwnKey)
    server.next()

    server.enqueue(MockResponse().setBody(members)); server.enqueue(MockResponse().setBody("""{"data":{},"success":true,"status":200}"""))
    assertTrue(e2e.handKeyTo(10) is ApiResult.Success)
    server.next()
    assertEquals("""{"chapter":1,"user":10,"wrappedOrgPrivateKey":"sealedkey(private-of-PUB-GROUP)to(PUB-NOOR)"}""", server.next().body.readUtf8())

    server.enqueue(MockResponse().setBody(members))
    assertTrue(e2e.handKeyTo(11) is ApiResult.Failure)
    assertEquals("only the members list was asked for", 4, server.requestCount)
  }

  @Test
  fun `sharing posts one envelope for the partner group and leaves the letter alone`() = runTest {
    val e2e = endToEnd()
    server.enqueue(MockResponse().setBody("""{"data":{"id":7,"chat":1,"sender":"user","prisoner":3,"ciphertext":"enc[x]","nonce":"n","envelopes":[{"readerType":"chapter","readerId":1,"wrappedKey":"${engine.sealedTo(groupPair)}"}]},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setBody("""{"data":{"chapter":2,"publicKey":"PUB-PARTNER","keyVersion":5},"success":true,"status":200}"""))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{},"success":true,"status":201}"""))
    assertTrue(e2e.shareWith(7, 2) is ApiResult.Success)
    server.next(); server.next()
    val post = server.next()
    assertEquals("/messaging/envelope", post.path)
    assertEquals("""{"message":7,"readerType":"chapter","readerId":2,"wrappedKey":"sealed(KEY)to(PUB-PARTNER)","keyVersion":5}""", post.body.readUtf8())
  }

  private object NoDirectory : DirectoryRepository {
    override fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>> = emptyFlow()
    override fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>> = emptyFlow()
    override fun groups(filter: GroupFilter): Flow<PagingData<Group>> = emptyFlow()
    override suspend fun featuredPrisoners(limit: Int) = ApiResult.Success(emptyList<Prisoner>())
    override suspend fun prisoner(id: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun facility(id: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun group(id: Int) = ApiResult.Failure(AppError.NotFound(null))
  }
}

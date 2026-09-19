package me.paxana.abcmailbox.data.crypto

import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.EnvelopeDto
import me.paxana.abcmailbox.data.api.MessageDto
import me.paxana.abcmailbox.data.repo.LetterEdit
import me.paxana.abcmailbox.data.repo.NewLetter
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
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

class LetterCodecTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val engine = FakeCryptoEngine()
  private val vault = InMemoryVault()
  private val sessions = FakeSessionRepository()
  private val keyring = FakeKeyring()
  private lateinit var authApi: AuthApi

  @Before
  fun setUp() = runTest {
    server.start()
    authApi = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    sessions.login("user1", "password1") // fake: user id 1
    vault.store(1, engine.keyPairFor("PUB-ME"))
  }

  @After fun tearDown() = server.shutdown()

  private fun codec(mode: EncryptionMode) = LetterCodec(FixedMode(mode), engine, vault, sessions, authApi, json, keyring, TestStrings())

  @Test
  fun `server mode passes plain text through and needs no keys`() = runTest {
    val (request, key) = (codec(EncryptionMode.SERVER).outgoing(NewLetter(3, "Dear friend", "two pages", 2)) as ApiResult.Success).value
    assertEquals("Dear friend", request.messageText)
    assertEquals("two pages", request.relayNote)
    assertNull(request.ciphertext)
    assertNull(key)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `a group's letter names the managed writer, and a recorded reply is from the prisoner with no relay fields`() = runTest {
    val c = codec(EncryptionMode.SERVER)
    val asWriter = (c.outgoing(NewLetter(3, "Hi", "note", 2, asWriterId = 44)) as ApiResult.Success).value.first
    assertEquals("""{"messageText":"Hi","prisoner":3,"sender":"user","user":44,"relayChapter":2,"relayNote":"note"}""", json.encodeToString(asWriter))
    val reply = (c.outgoing(NewLetter(3, "Thank you", "ignored", 2, asWriterId = 4, fromPrisoner = true)) as ApiResult.Success).value.first
    assertEquals("""{"messageText":"Thank you","prisoner":3,"sender":"prisoner","user":4}""", json.encodeToString(reply))
  }

  @Test
  fun `end-to-end seals to the writer and to the relay group with its key version`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"chapter":2,"publicKey":"PUB-GROUP","keyVersion":4},"success":true,"status":200}"""))
    val (request, key) = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Dear friend", "two pages", 2)) as ApiResult.Success).value
    assertEquals("/auth/public-key?chapter=2", server.next().path)
    assertNull("plaintext must never be sent", request.messageText)
    assertNull(request.relayNote)
    assertEquals("enc[Dear friend]", request.ciphertext)
    assertEquals("enc[two pages]", request.relayNoteCiphertext)
    assertEquals(
      // The writer's public key travels as base64, like every key on the wire.
      listOf(EnvelopeDto("user", 1, "sealed(KEY)to(${java.util.Base64.getEncoder().encodeToString("PUB-ME".toByteArray())})"), EnvelopeDto("chapter", 2, "sealed(KEY)to(PUB-GROUP)", keyVersion = 4)),
      request.envelopes,
    )
    assertEquals("KEY", String(key!!))
  }

  @Test
  fun `a relay group without keys cannot be written to, and says so`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":{"chapter":2,"publicKey":null,"keyVersion":0},"success":true,"status":200}"""))
    val r = codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Dear friend", null, 2)) as ApiResult.Failure
    assertTrue((r.error as AppError.Validation).errors.single().contains("has not set up encryption"))
  }

  @Test
  fun `a locked vault refuses to send rather than sending something unreadable`() = runTest {
    vault.clear()
    val r = codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Dear friend", null, null)) as ApiResult.Failure
    assertEquals(lockedError(TestStrings()), r.error)
  }

  private val sealedToMe get() = engine.sealedTo(engine.keyPairFor("PUB-ME"))

  private fun encrypted(envelopes: List<EnvelopeDto>) = MessageDto(id = 9, chat = 4, sender = "user", prisoner = 3, user = 1, status = "queued",
    ciphertext = "enc[the body]", nonce = "n", relayNoteCiphertext = "enc[the note]", relayNoteNonce = "n", envelopes = envelopes)

  @Test
  fun `incoming letters open with my envelope, and stay locked without one`() = runTest {
    val c = codec(EncryptionMode.E2E)
    val mine = c.incoming(encrypted(listOf(EnvelopeDto("chapter", 2, "sealed(KEY)to(PUB-GROUP)", 1), EnvelopeDto("user", 1, sealedToMe))))
    assertEquals("the body", mine.body); assertEquals("the note", mine.relayNote); assertEquals(false, mine.locked)

    val notMine = c.incoming(encrypted(listOf(EnvelopeDto("user", 99, "sealed(KEY)to(PUB-OTHER)"))))
    assertTrue(notMine.locked); assertEquals("", notMine.body)

    vault.clear()
    assertTrue(c.incoming(encrypted(listOf(EnvelopeDto("user", 1, sealedToMe)))).locked)
  }

  @Test
  fun `an edit re-encrypts under the existing key and does not touch the readers`() = runTest {
    val existing = encrypted(listOf(EnvelopeDto("user", 1, sealedToMe)))
    val req = (codec(EncryptionMode.E2E).edit(LetterEdit(9, "edited body", null, 2), existing) as ApiResult.Success).value
    assertEquals("enc[edited body]", req.ciphertext)
    assertNull(req.messageText); assertNull(req.relayChapter); assertNull(req.relayNoteCiphertext)
  }

  // A group member on an end-to-end server ------------------------------------------------------

  private val groupPair get() = engine.keyPairFor("PUB-GROUP")
  private fun asMember() {
    sessions.signInAs(me.paxana.abcmailbox.data.session.SessionUser(9, "member1", "Sam", null, "chapter", 1))
    keyring.state.value = GroupKeyState.Ready(GroupKey(1, groupPair, "PUB-GROUP", 3))
  }
  private fun publicKey(body: String) = MockResponse().setBody("""{"data":$body,"success":true,"status":200}""")

  @Test
  fun `an anonymous group letter has one envelope, the group's own, and asks for no other key`() = runTest {
    asMember()
    val request = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "From a friend", null, relayChapter = 1)) as ApiResult.Success).value.first
    assertEquals(listOf(EnvelopeDto("chapter", 1, "sealed(KEY)to(PUB-GROUP)", keyVersion = 3)), request.envelopes)
    assertNull(request.user); assertEquals("user", request.sender)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `a letter for a managed writer is sealed to the writer, the managing group, and a different relay group`() = runTest {
    asMember(); keyring.custody[44] = engine.keyPairFor("PUB-ALEX")
    server.enqueue(publicKey("""{"user":44,"publicKey":"PUB-ALEX"}"""))
    server.enqueue(publicKey("""{"chapter":2,"publicKey":"PUB-RELAY","keyVersion":7}"""))
    val request = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Hi", "note", relayChapter = 2, asWriterId = 44)) as ApiResult.Success).value.first
    assertEquals("/auth/public-key?user=44", server.next().path)
    assertEquals("/auth/public-key?chapter=2", server.next().path)
    assertEquals(listOf(
      EnvelopeDto("user", 44, "sealed(KEY)to(PUB-ALEX)"),
      EnvelopeDto("chapter", 1, "sealed(KEY)to(PUB-GROUP)", keyVersion = 3),
      EnvelopeDto("chapter", 2, "sealed(KEY)to(PUB-RELAY)", keyVersion = 7)), request.envelopes)
    assertEquals(44, request.user); assertEquals("enc[note]", request.relayNoteCiphertext)
  }

  @Test
  fun `a recorded reply is sealed to the writer, and to the group only where the server would allow it`() = runTest {
    asMember()
    server.enqueue(publicKey("""{"user":4,"publicKey":"PUB-WRITER"}"""))
    val stranger = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Thank you", "ignored", relayChapter = null, asWriterId = 4, fromPrisoner = true)) as ApiResult.Success).value.first
    assertEquals(listOf(EnvelopeDto("user", 4, "sealed(KEY)to(PUB-WRITER)")), stranger.envelopes)
    assertEquals("prisoner", stranger.sender); assertNull(stranger.relayChapter); assertNull(stranger.relayNoteCiphertext)

    server.enqueue(publicKey("""{"user":4,"publicKey":"PUB-WRITER"}"""))
    val relayed = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Thank you", null, relayChapter = null, asWriterId = 4, fromPrisoner = true, groupRelaysFacility = true)) as ApiResult.Success).value.first
    assertEquals(listOf("user" to 4, "chapter" to 1), relayed.envelopes!!.map { it.readerType to it.readerId })
  }

  @Test
  fun `a member without the group key is told why, and nothing is sent anywhere`() = runTest {
    asMember(); keyring.state.value = GroupKeyState.NotHeld(1)
    val r = codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Hi", null, 1)) as ApiResult.Failure
    assertTrue((r.error as AppError.Forbidden).info.contains("not been given your group's key"))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `a writer with no key yet cannot be written for`() = runTest {
    asMember()
    server.enqueue(publicKey("""{"user":44,"publicKey":null}"""))
    assertTrue(codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Hi", null, 1, asWriterId = 44)) is ApiResult.Failure)
  }

  @Test
  fun `a member reads through the group's envelope, or through a writer's key held in custody, but not a stranger's`() = runTest {
    asMember(); val c = codec(EncryptionMode.E2E)
    fun dto(vararg envelopes: EnvelopeDto) = MessageDto(id = 7, chat = 1, sender = "user", prisoner = 3, ciphertext = "enc[Dear Jane]", nonce = "n", envelopes = envelopes.toList())
    assertEquals("Dear Jane", c.incoming(dto(EnvelopeDto("chapter", 1, engine.sealedTo(groupPair)))).body)

    val alex = engine.keyPairFor("PUB-ALEX")
    assertTrue("custody key not loaded yet", c.incoming(dto(EnvelopeDto("user", 44, engine.sealedTo(alex)))).locked)
    keyring.custody[44] = alex
    assertEquals("Dear Jane", c.incoming(dto(EnvelopeDto("user", 44, engine.sealedTo(alex)))).body)

    assertTrue(c.incoming(dto(EnvelopeDto("chapter", 2, "sealed(KEY)to(another group)"))).locked)
  }

  @Test
  fun `ready loads the keyring for members only, and a rotation forces a second look`() = runTest {
    val c = codec(EncryptionMode.E2E)
    c.ready(); assertEquals("a writer never touches the keyring", 0, keyring.loads)
    asMember(); c.ready(); c.refreshKeys()
    assertEquals(2, keyring.loads); assertEquals(1, keyring.forced)
  }

  @Test
  fun `sharing seals the letter's own content key to the partner, with the partner's key version`() = runTest {
    asMember(); val c = codec(EncryptionMode.E2E)
    server.enqueue(publicKey("""{"chapter":2,"publicKey":"PUB-PARTNER","keyVersion":5}"""))
    val dto = MessageDto(id = 7, chat = 1, sender = "user", prisoner = 3, ciphertext = "enc[x]", nonce = "n", envelopes = listOf(EnvelopeDto("chapter", 1, engine.sealedTo(groupPair))))
    assertEquals(EnvelopeDto("chapter", 2, "sealed(KEY)to(PUB-PARTNER)", keyVersion = 5), (c.envelopeFor(dto, 2) as ApiResult.Success).value)
  }

  @Test
  fun `a reply for a writer who has no key yet is sealed to the group alone, where the group may hold one`() = runTest {
    asMember()
    server.enqueue(publicKey("""{"user":4,"publicKey":null}"""))
    val reply = (codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Thank you", null, relayChapter = null, asWriterId = 4, fromPrisoner = true, groupRelaysFacility = true)) as ApiResult.Success).value.first
    assertEquals(listOf(EnvelopeDto("chapter", 1, "sealed(KEY)to(PUB-GROUP)", keyVersion = 3)), reply.envelopes)

    // Nobody at all could read it: refuse rather than store a letter without a reader.
    server.enqueue(publicKey("""{"user":4,"publicKey":null}"""))
    assertTrue(codec(EncryptionMode.E2E).outgoing(NewLetter(3, "Thank you", null, relayChapter = null, asWriterId = 4, fromPrisoner = true)) is ApiResult.Failure)
  }
}

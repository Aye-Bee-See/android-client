package me.paxana.abcmailbox.data.crypto

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
  private lateinit var authApi: AuthApi

  @Before
  fun setUp() = runTest {
    server.start()
    authApi = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    sessions.login("user1", "password1") // fake: user id 1
    vault.store(1, engine.keyPairFor("PUB-ME"))
  }

  @After fun tearDown() = server.shutdown()

  private fun codec(mode: EncryptionMode) = LetterCodec(FixedMode(mode), engine, vault, sessions, authApi, json)

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
    assertEquals("/auth/public-key?chapter=2", server.takeRequest().path)
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
    assertEquals(LetterCodec.LOCKED, r.error)
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
}

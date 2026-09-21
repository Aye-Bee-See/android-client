package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ReturnReason
import me.paxana.abcmailbox.domain.HeldReason
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
import android.net.Uri
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import me.paxana.abcmailbox.data.api.AppError
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/** Wire-level checks the API's conventions require: DELETE with a body, nulls omitted, multipart layout. */
class LettersRepositoryTest {

  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private lateinit var repo: DefaultLettersRepository
  private val tmp = File(System.getProperty("java.io.tmpdir"), "abc-test-${System.nanoTime()}").apply { mkdirs() }

  @Before
  fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/"))
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
      .build().create(LettersApi::class.java)
    val authApi = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(me.paxana.abcmailbox.data.api.AuthApi::class.java)
    val codec = me.paxana.abcmailbox.data.crypto.LetterCodec(me.paxana.abcmailbox.data.crypto.FixedMode(me.paxana.abcmailbox.data.crypto.EncryptionMode.SERVER), me.paxana.abcmailbox.data.crypto.FakeCryptoEngine(), me.paxana.abcmailbox.data.crypto.InMemoryVault(), me.paxana.abcmailbox.ui.auth.FakeSessionRepository(), authApi, json, me.paxana.abcmailbox.data.crypto.FakeKeyring(), TestStrings())
    repo = DefaultLettersRepository(api, json, FakeLocalFiles(tmp), codec)
  }

  @After
  fun tearDown() { server.shutdown(); tmp.deleteRecursively() }

  private fun fixture(name: String) = javaClass.getResourceAsStream("/letters/$name")!!.reader().readText()

  @Test
  fun `send omits relayChapter and relayNote when unset`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(fixture("message-full.json")))
    val sent = repo.send(NewLetter(prisonerId = 3, body = "Dear friend", relayNote = null, relayChapter = null))
    assertTrue("the send itself failed: $sent", sent is ApiResult.Success)
    val req = server.next()
    assertEquals("POST", req.method)
    assertEquals("""{"messageText":"Dear friend","prisoner":3,"sender":"user"}""", req.body.readUtf8())
  }

  @Test
  fun `send includes a chosen relay group and a note`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(fixture("message-full.json")))
    repo.send(NewLetter(3, "Hi", "two pages", 2))
    assertEquals("""{"messageText":"Hi","prisoner":3,"sender":"user","relayChapter":2,"relayNote":"two pages"}""", server.next().body.readUtf8())
  }

  // Returned mail and held letters (API PRs #105, #106). The fixtures were recorded from the API on 20 Sep 2026.

  @Test
  fun `a conversation tells a returned letter's whole story, though the server sends it in pieces`() = runTest {
    // Inside a conversation a letter has its reason but no history (so no note), and not what replaced it.
    server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
      override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
        request.path!!.startsWith("/chat/chat") -> MockResponse().setBody(fixture("chat-returned-and-held.json"))
        request.path!!.startsWith("/messaging/message?id=1&") -> MockResponse().setBody(fixture("message-returned-full.json"))
        else -> MockResponse().setResponseCode(500) // the other returned letters: their notes are a nicety, and their failure must cost nothing
      }
    }
    val thread = (repo.thread(1) as ApiResult.Success).value
    val first = thread.letters.first { it.id == 1 }
    assertEquals(LetterStatus.RETURNED, first.status); assertEquals(ReturnReason.TRANSFERRED, first.returnReason)
    assertEquals("the note is on the history row, fetched for returned letters only", "Stamped NOT HERE", first.returnNote)
    assertEquals("what replaced it is read off the conversation: letter 6 says resendOf 1", listOf(6), first.resentAs.map { it.id })
    assertTrue("sent again already, so not offered twice", !first.canSendAgain)

    val second = thread.letters.first { it.id == 2 }
    assertEquals(ReturnReason.RULE_VIOLATION, second.returnReason); assertEquals(null, second.returnNote); assertTrue(second.canSendAgain)

    val held = thread.letters.first { it.id == 3 }
    assertTrue(held.isHeld); assertEquals(HeldReason.CHOOSE_RELAY, held.heldReason); assertTrue("still the writer's to edit: that is how the choice is made", held.canEdit)
    assertEquals(1, thread.letters.first { it.id == 6 }.resendOfId)
    val asked = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
    assertEquals("one full read per returned letter, none for the rest", 3, asked.count { it.startsWith("/messaging/message") })
  }

  @Test
  fun `a reason or a hold this version has never heard of is still a return, still a hold`() {
    assertEquals(ReturnReason.UNKNOWN, ReturnReason.fromKey("lost_in_flood")); assertEquals(null, ReturnReason.fromKey(null))
    assertEquals(HeldReason.OTHER, HeldReason.fromKey("awaiting_censor")); assertEquals(null, HeldReason.fromKey(null)); assertEquals(null, HeldReason.fromKey(""))
  }

  @Test
  fun `sending again names the letter that came back`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(fixture("message-full.json")))
    repo.send(NewLetter(prisonerId = 1, body = "Dear Sam", relayNote = null, relayChapter = null, resendOf = 41))
    assertEquals("""{"messageText":"Dear Sam","prisoner":1,"sender":"user","resendOf":41}""", server.next().body.readUtf8())
  }

  @Test
  fun `a held letter that had to be sent again is removed only after its replacement is safely there`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(fixture("message-full.json")))
    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    assertTrue(repo.send(NewLetter(prisonerId = 1, body = "Dear Sam", relayNote = null, relayChapter = null, replacesHeld = 3)) is ApiResult.Success)
    assertEquals("POST", server.next().method)
    val removal = server.next(); assertEquals("DELETE", removal.method); assertEquals("""{"id":3}""", removal.body.readUtf8())

    // The other way round: the send fails, and the held letter is left exactly where it was.
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errors":["No relay group serves this facility."]}"""))
    assertTrue(repo.send(NewLetter(prisonerId = 1, body = "Dear Sam", relayNote = null, relayChapter = null, replacesHeld = 3)) is ApiResult.Failure)
    server.next(); assertEquals("nothing was deleted", 3, server.requestCount)
  }

  @Test
  fun `delete sends the id in a JSON body`() = runTest {
    server.enqueue(MockResponse().setBody("""{"data":1,"success":true,"status":200}"""))
    val r = repo.delete(41)
    val req = server.next()
    assertEquals("DELETE", req.method)
    assertEquals("/messaging/message", req.path)
    assertEquals("""{"id":41}""", req.body.readUtf8())
    assertTrue(r is ApiResult.Success)
  }

  @Test
  fun `upload is multipart with a message field and a file part`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":5,"message":41,"originalName":"scan.png","mimeType":"image/png","size":3},"success":true,"status":201}"""))
    val f = File(tmp, "scan.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val r = repo.upload(41, StagedFile(f, "scan.png", "image/png", 3))
    val req = server.next()
    val body = req.body.readUtf8()
    assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    assertTrue(body.contains("name=\"message\""))
    assertTrue(body.contains("41"))
    assertTrue(body.contains("name=\"file\"; filename=\"scan.png\""))
    assertTrue(body.contains("Content-Type: image/png"))
    assertEquals("scan.png", (r as ApiResult.Success).value.name)
  }

  @Test
  fun `download writes the bytes to the cache target`() = runTest {
    server.enqueue(MockResponse().setBody("hello").setHeader("Content-Type", "application/pdf"))
    val att = me.paxana.abcmailbox.domain.Attachment(7, 41, "reply.pdf", "application/pdf", 5)
    val r = repo.download(att) as ApiResult.Success
    assertEquals("hello", r.value.readText())
    assertEquals("/messaging/attachment?id=7", server.next().path)
    // Second call is served from the cache: no request.
    repo.download(att)
    assertEquals(1, server.requestCount)
  }

  /** LocalFiles needs a Context; on the JVM we only need the target path logic. */
  @Test
  fun `the letter's key travels as the Idempotency-Key header, and a letter without one sends no such header`() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":9,"chat":1,"sender":"user","prisoner":3,"messageText":"Hi"},"success":true,"status":201}"""))
    repo.send(NewLetter(3, "Hi", null, null, idempotencyKey = "6f1c2a0e-8d1b-4a53-9c0e-2f6b7f0d9a11"))
    assertEquals("6f1c2a0e-8d1b-4a53-9c0e-2f6b7f0d9a11", server.next().getHeader("Idempotency-Key"))

    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":10,"chat":1,"sender":"user","prisoner":3,"messageText":"Hi"},"success":true,"status":201}"""))
    repo.send(NewLetter(3, "Hi", null, null))
    assertNull(server.next().getHeader("Idempotency-Key"))
  }

  @Test
  fun `a retry that is still in flight is handed back as it is, not retried on the spot like a rotated key`() = runTest {
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"IdempotencyError","info":"Still being processed.","status":409}"""))
    val r = repo.send(NewLetter(3, "Hi", null, null, idempotencyKey = "k-12345678")) as ApiResult.Failure
    assertTrue((r.error as AppError.Conflict).isStillProcessing)
    assertEquals(1, server.requestCount)
  }

  private class FakeLocalFiles(private val dir: File) : LocalFilesContract {
    override fun newCameraTarget(): Pair<File, Uri> = error("not used")
    override fun stageCameraShot(file: File): StagedFile = StagedFile(file, file.name, "image/jpeg", file.length())
    override suspend fun stage(uri: Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) = Unit
    override fun downloadTarget(attachmentId: Int, name: String) = File(dir, "${attachmentId}_$name")
  }
}

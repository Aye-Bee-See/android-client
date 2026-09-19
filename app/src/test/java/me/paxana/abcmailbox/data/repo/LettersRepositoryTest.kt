package me.paxana.abcmailbox.data.repo

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

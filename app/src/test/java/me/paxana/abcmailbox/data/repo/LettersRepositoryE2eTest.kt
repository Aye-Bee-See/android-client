package me.paxana.abcmailbox.data.repo

import android.net.Uri
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.api.LettersApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/** End-to-end mode at the repository level: the key-rotation retry and encrypted attachments. */
class LettersRepositoryE2eTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val engine = FakeCryptoEngine()
  private val vault = InMemoryVault()
  private val sessions = FakeSessionRepository()
  private lateinit var repo: DefaultLettersRepository
  private val tmp = File(System.getProperty("java.io.tmpdir"), "abc-e2e-${System.nanoTime()}").apply { mkdirs() }

  @Before
  fun setUp() = runTest {
    server.start()
    val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    sessions.login("user1", "password1")
    vault.store(1, engine.keyPairFor("PUB-ME"))
    val codec = LetterCodec(FixedMode(EncryptionMode.E2E), engine, vault, sessions, retrofit.create(AuthApi::class.java), json, me.paxana.abcmailbox.data.crypto.FakeKeyring())
    repo = DefaultLettersRepository(retrofit.create(LettersApi::class.java), json, object : LocalFilesContract {
      override fun newCameraTarget(): Pair<File, Uri> = error("not used")
      override fun stageCameraShot(file: File): StagedFile = StagedFile(file, file.name, "image/jpeg", file.length())
      override suspend fun stage(uri: Uri): StagedFile = error("not used")
      override fun discard(staged: StagedFile) = Unit
      override fun downloadTarget(attachmentId: Int, name: String) = File(tmp, "${attachmentId}_$name")
    }, codec)
  }

  @After fun tearDown() { server.shutdown(); tmp.deleteRecursively() }

  private fun publicKey(version: Int) = MockResponse().setBody("""{"data":{"chapter":2,"publicKey":"PUB-GROUP-V$version","keyVersion":$version},"success":true,"status":200}""")
  private val myEnvelope = java.util.Base64.getEncoder().encodeToString("PUB-ME".toByteArray()).let { """{"readerType":"user","readerId":1,"wrappedKey":"sealed(KEY)to($it)"}""" }

  @Test
  fun `when the group rotated its key mid-send, the letter is re-sealed to the new key and sent again`() = runTest {
    server.enqueue(publicKey(1))
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"name":"KeyVersionError","info":"That group has rotated its key.","status":409}"""))
    server.enqueue(publicKey(2))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":9,"chat":4,"sender":"user","prisoner":3,"user":1,"status":"queued","relayChapter":2,"ciphertext":"enc[Dear friend]","nonce":"nonce","envelopes":[$myEnvelope]},"success":true,"status":201}"""))

    val sent = repo.send(NewLetter(3, "Dear friend", null, 2)) as ApiResult.Success
    assertEquals("Dear friend", sent.value.body)

    server.takeRequest() // public key v1
    val first = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["envelopes"]!!.jsonArray[1].jsonObject
    assertEquals(1, first["keyVersion"]!!.jsonPrimitive.int)
    assertEquals("/auth/public-key?chapter=2", server.takeRequest().path)
    val second = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["envelopes"]!!.jsonArray[1].jsonObject
    assertEquals(2, second["keyVersion"]!!.jsonPrimitive.int)
    assertEquals("sealed(KEY)to(PUB-GROUP-V2)", second["wrappedKey"]!!.jsonPrimitive.content)
    assertEquals(4, server.requestCount)
  }

  @Test
  fun `an attachment is uploaded as ciphertext with its nonce, and comes back decrypted`() = runTest {
    val message = """{"data":{"id":9,"sender":"user","prisoner":3,"user":1,"status":"queued","ciphertext":"enc[x]","nonce":"n","envelopes":[$myEnvelope]},"success":true,"status":200}"""
    server.enqueue(MockResponse().setBody(message))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"data":{"id":5,"message":9,"originalName":"scan.png","mimeType":"image/png","size":3,"nonce":"file-nonce"},"success":true,"status":201}"""))
    val file = File(tmp, "scan.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val uploaded = (repo.upload(9, StagedFile(file, "scan.png", "image/png", 3)) as ApiResult.Success).value
    assertEquals("file-nonce", uploaded.nonce)

    server.takeRequest()
    val body = server.takeRequest().body
    val raw = body.readByteArray()
    val text = String(raw, Charsets.ISO_8859_1)
    assertTrue(text.contains("name=\"nonce\"")); assertTrue(text.contains("file-nonce"))
    assertTrue("the plaintext bytes 1,2,3 must not be uploaded in order", !text.contains(String(byteArrayOf(1, 2, 3), Charsets.ISO_8859_1)))
    assertTrue(text.contains(String(byteArrayOf(3, 2, 1), Charsets.ISO_8859_1))) // the fake engine "encrypts" by reversing

    server.enqueue(MockResponse().setBody(message))
    server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(3, 2, 1))))
    val downloaded = (repo.download(uploaded) as ApiResult.Success).value
    assertEquals(listOf<Byte>(1, 2, 3), downloaded.readBytes().toList())
  }
}

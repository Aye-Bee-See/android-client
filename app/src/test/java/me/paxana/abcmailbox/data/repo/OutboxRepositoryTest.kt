package me.paxana.abcmailbox.data.repo

import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.data.crypto.lockedError
import android.net.Uri
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FakeKeyring
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.data.crypto.LetterCodec
import me.paxana.abcmailbox.data.db.OutboxDao
import me.paxana.abcmailbox.data.db.OutboxEntity
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.Thread
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

/** The promise under test: a letter written offline is sent once, exactly once, or the writer is told why not. */
class OutboxRepositoryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val dao = FakeOutboxDao()
  private val letters = ScriptedLetters()
  private val sessions = FakeSessionRepository()
  private var scheduled = 0
  private val scheduledAt = mutableListOf<Instant>()
  private var clock = Instant.parse("2026-09-27T20:00:00Z")
  private lateinit var outbox: DefaultOutboxRepository

  /** Reverses bytes: not encryption, but enough to show that what reaches the disk is not the plain text. */
  private object Reversing : SecretCipher {
    /** The Keystore failing in passing, as it does on some phones: for everything, or only for blobs under a size (a file, not the letter). */
    var failing = false; var failingBelow = 0
    override fun encrypt(plain: ByteArray) = plain.reversedArray()
    override fun decrypt(blob: ByteArray) = if (failing || blob.size < failingBelow) throw IllegalStateException("Keystore busy") else blob.reversedArray()
  }

  @Before
  fun setUp() {
    server.start()
    val retrofit = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    val codec = LetterCodec(FixedMode(EncryptionMode.SERVER), FakeCryptoEngine(), InMemoryVault(), sessions, retrofit.create(AuthApi::class.java), json, FakeKeyring(), TestStrings())
    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
    outbox = DefaultOutboxRepository(dao, Reversing, Files(tmp.root), letters, codec, sessions, object : OutboxScheduler { override fun schedule() { scheduled++ }; override fun scheduleAt(at: Instant) { scheduledAt += at } }, json, TestStrings())
    outbox.now = { clock }
  }

  @After fun tearDown() { Reversing.failing = false; Reversing.failingBelow = 0; server.shutdown() }

  private val letter = NewLetter(prisonerId = 1, body = "Dear Jane, written in the basement.", relayNote = null, relayChapter = 1)
  private fun staged(name: String) = StagedFile(tmp.newFile(name).apply { writeText("scan of $name") }, name, "application/pdf", 12)
  private suspend fun rows() = dao.rows.value
  private fun messages(vararg rows: String) = MockResponse().setBody("""{"data":[${rows.joinToString(",")}],"success":true,"status":200}""")
  private fun onServer(id: Int, text: String, user: Int = 2, at: Instant = Instant.now()) = """{"id":$id,"chat":9,"sender":"user","prisoner":1,"user":$user,"messageText":"$text","createdAt":"$at"}"""

  @Test
  fun `a queued letter is unreadable on disk, its file leaves the cache, and a send is scheduled`() = runTest {
    val file = staged("scan.pdf")
    outbox.queue("Jane Smith", null, letter, listOf(file))
    val row = rows().single()
    assertFalse("the recipient and the text are ciphertext", row.sealed.contains("Jane") || row.sealed.contains("basement"))
    assertFalse("the staged copy is gone", file.file.exists())
    val item = outbox.items().first().single()
    assertEquals("Jane Smith", item.payload.prisonerName); assertNull(item.problem)
    assertFalse("the stored file is ciphertext too", File(item.payload.attachments.single().path).readText().contains("scan of"))
    assertEquals(1, scheduled)
  }

  @Test
  fun `online again, the letter goes, then its files, and nothing is left behind`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("a.pdf"), staged("b.pdf")))
    val stored = outbox.items().first().single().payload.attachments.map { File(it.path) }
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals(listOf(letter.body), letters.sent.map { it.body })
    assertEquals(listOf("a.pdf" to "scan of a.pdf", "b.pdf" to "scan of b.pdf"), letters.uploaded)
    assertTrue(rows().isEmpty()); assertTrue(stored.none { it.exists() })
  }

  @Test
  fun `still no connection means wait, and keep the order`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList()); outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2), emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Network(UnknownHostException()))
    assertEquals(FlushOutcome(stillWaiting = 2, tryAgain = true), outbox.flush())
    assertTrue("the second letter was not tried through the same dead connection", letters.sent.isEmpty())
    assertEquals(FlushOutcome(sent = 2), outbox.flush())
  }

  @Test
  fun `a 429 waits out Retry-After, asks nothing before then, books a run for the end, then sends under the same key`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList()); outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2), emptyList())
    letters.sendResults += ApiResult.Failure(AppError.RateLimited("Too many letters and replies. Try again in 45 minute(s).", 2700))
    val until = Instant.parse("2026-09-27T20:45:00Z")
    assertEquals(FlushOutcome(stillWaiting = 2, limitedUntil = until), outbox.flush())
    assertEquals("a run booked for the end of the wait", listOf(until), scheduledAt)
    assertEquals(until, outbox.limitedUntil.value)
    assertTrue("paced, not refused", rows().none { it.state == OutboxEntity.STATE_REFUSED })

    // "Send now", or the network coming back, before the wait is over: nothing is asked.
    clock = Instant.parse("2026-09-27T20:30:00Z")
    assertEquals(FlushOutcome(stillWaiting = 2, limitedUntil = until), outbox.flush())
    assertEquals("only the one limited try", 1, letters.sendKeys.size)

    clock = Instant.parse("2026-09-27T20:45:01Z")
    assertEquals(FlushOutcome(sent = 2), outbox.flush())
    assertEquals("the retry is the same letter under the same key", letters.sendKeys[0], letters.sendKeys[1])
    assertNull(outbox.limitedUntil.value)
  }

  @Test
  fun `a 429 on a file after its letter went waits too, and the letter is not sent again`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("a.pdf")))
    letters.uploadResults += ApiResult.Failure(AppError.RateLimited(null, null))
    val outcome = outbox.flush()
    assertEquals("without Retry-After, a minute", Instant.parse("2026-09-27T20:01:00Z"), outcome.limitedUntil)
    assertEquals(1, outcome.stillWaiting)
    clock = Instant.parse("2026-09-27T20:01:01Z")
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals(1, letters.sent.size); assertEquals(listOf("a.pdf" to "scan of a.pdf"), letters.uploaded)
  }

  @Test
  fun `every try of a letter carries the same key, and each file its own`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("a.pdf"), staged("b.pdf")))
    letters.sendResults += ApiResult.Failure(AppError.Network(SocketTimeoutException()))   // may have arrived
    letters.sendResults += ApiResult.Failure(AppError.Server(502, null))                   // may have arrived
    outbox.flush(); outbox.flush(); outbox.flush()
    assertEquals("three tries, one key", 1, letters.sendKeys.toSet().size)
    assertEquals(3, letters.sendKeys.size); assertTrue(letters.sendKeys.first()!!.length >= 8)
    assertEquals("the server made it one letter", listOf(letter.body), letters.sent.map { it.body })
    assertEquals(2, letters.uploadKeys.toSet().size)
    assertTrue("a file's key is not the letter's", letters.uploadKeys.none { it == letters.sendKeys.first() })
  }

  @Test
  fun `a key the compose screen already tried with is kept, because that try may have arrived`() = runTest {
    outbox.queue("Jane Smith", null, letter.copy(idempotencyKey = "key-from-the-first-try"), emptyList())
    outbox.flush()
    assertEquals(listOf<String?>("key-from-the-first-try"), letters.sendKeys)
  }

  @Test
  fun `a retry racing its own earlier attempt waits, and is not mistaken for a refusal`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Conflict("A request with this key is still being processed.", "IdempotencyError"))
    assertEquals(FlushOutcome(stillWaiting = 1, tryAgain = true), outbox.flush())
    assertNull(outbox.items().first().single().problem)
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
  }

  @Test
  fun `a letter that was sent and has since been deleted elsewhere is dropped, not sent again and not reported`() = runTest {
    val id = outbox.queue("Jane Smith", null, letter, listOf(staged("scan.pdf")))
    val stored = File(outbox.items().first().single().payload.attachments.single().path)
    letters.sendResults += ApiResult.Failure(AppError.Gone("This letter was deleted."))
    assertEquals(FlushOutcome(), outbox.flush())
    assertTrue(rows().isEmpty()); assertFalse(stored.exists()); assertTrue(letters.sent.isEmpty())
  }

  @Test
  fun `a key refused as belonging to a different letter is a refusal to show, not something to retry for ever`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Validation(listOf("This Idempotency-Key was used for a different letter.")))
    assertEquals(FlushOutcome(refused = 1), outbox.flush())
    assertEquals("This Idempotency-Key was used for a different letter.", outbox.items().first().single().problem)
  }

  @Test
  fun `a refusal is kept with the server's reason, and can be tried again as it is`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Forbidden("This facility accepts letters only through a relay group."))
    assertEquals(FlushOutcome(refused = 1), outbox.flush())
    val item = outbox.items().first().single()
    assertEquals("This facility accepts letters only through a relay group.", item.problem); assertFalse(item.letterWasSent)
    assertEquals("a refused letter is not retried by itself", FlushOutcome(), outbox.flush())

    outbox.retry(item.id)
    assertNull(outbox.items().first().single().problem)
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
  }

  @Test
  fun `a refused file does not un-send the letter, and the letter is never posted again`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("huge.pdf")))
    letters.uploadResults += ApiResult.Failure(AppError.Validation(listOf("The file is larger than 20 MB.")))
    assertEquals(FlushOutcome(refused = 1), outbox.flush())
    val item = outbox.items().first().single()
    assertTrue(item.letterWasSent)
    assertEquals("The letter was sent, but huge.pdf could not be attached: The file is larger than 20 MB.", item.problem)
    outbox.retry(item.id); outbox.flush()
    assertEquals("one letter, however many tries", 1, letters.sent.size)
  }

  @Test
  fun `signed out, or signed in as someone else, nothing of this account's is sent or shown`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    sessions.signInAs(SessionUser(3, "user2", null, null, "user", null))
    assertTrue(outbox.items().first().isEmpty())
    assertEquals(FlushOutcome(), outbox.flush())
    sessions.logout()
    outbox.flush()
    assertTrue(letters.sent.isEmpty()); assertTrue(outbox.hasWaiting())
    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
  }

  @Test
  fun `a deleted account's unsent letters and their files go, and only that account's`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("scan.pdf")))
    sessions.signInAs(SessionUser(3, "user2", null, null, "user", null))
    outbox.queue("Sam Example", null, letter, listOf(staged("photo.jpg")))
    val filesBefore = tmp.root.listFiles()!!.count { it.name.startsWith("outbox-") }
    assertEquals(2, filesBefore)

    // By id, not by "whoever is signed in": by the time this runs for real, nobody is.
    sessions.logout()
    assertEquals(1, outbox.eraseFor(2))
    assertEquals(listOf(3), rows().map { it.userId })
    assertEquals("user1's encrypted file is gone, user2's is still waiting", 1, tmp.root.listFiles()!!.count { it.name.startsWith("outbox-") })
    assertEquals("nothing to erase twice", 0, outbox.eraseFor(2))
  }

  @Test
  fun `a letter sent again without signal still remembers what it replaces when it finally goes`() = runTest {
    outbox.queue("Jane Smith", null, letter.copy(resendOf = 41, replacesHeld = null), emptyList())
    outbox.queue("Jane Smith", null, letter.copy(body = "another", replacesHeld = 52), emptyList())
    outbox.flush()
    assertEquals(listOf(41, null), letters.sent.map { it.resendOf }); assertEquals(listOf(null, 52), letters.sent.map { it.replacesHeld })
  }

  @Test
  fun `reopening a queued letter hands back its text and its files, readable again`() = runTest {
    val id = outbox.queue("Jane Smith", "Rosa L.", letter.copy(asWriterId = 43), listOf(staged("scan.pdf")))
    val (payload, files) = outbox.open(id)!!
    assertEquals(letter.body, payload.body); assertEquals("Rosa L.", payload.writingAs); assertEquals(43, payload.asWriterId)
    assertEquals("scan of scan.pdf", files.single().file.readText())
    outbox.forget(id)
    assertTrue(rows().isEmpty())
  }

  @Test
  fun `a run that starts before the stored session has been read waits for it, and does not take nobody to be signed in`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    sessions.notReadYet() // a cold process: the worker is running, the session store is still being read
    val run = async { outbox.flush() }
    testScheduler.runCurrent()
    assertTrue("nothing decided yet", letters.sent.isEmpty())
    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
    assertEquals(FlushOutcome(sent = 1), run.await())
  }

  @Test
  fun `a letter whose blob will not open this time is left for the next run, not thrown away`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    Reversing.failing = true
    assertEquals("nothing to say, nothing to retry for: it is left as it is", FlushOutcome(), outbox.flush())
    assertEquals(1, rows().size); assertTrue(letters.sent.isEmpty())
    Reversing.failing = false
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
  }

  @Test
  fun `a file that will not open this time waits for the next run, after its letter went, and is not a refusal`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("a.pdf")))
    Reversing.failingBelow = 64 // the file's few bytes, not the letter's JSON
    assertEquals(FlushOutcome(), outbox.flush())
    assertEquals("the letter went", 1, letters.sent.size); assertTrue("the file did not", letters.uploaded.isEmpty())
    val row = rows().single()
    assertEquals(99, row.messageId); assertEquals("not set aside", OutboxEntity.STATE_WAITING, row.state)
    Reversing.failingBelow = 0
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals("the letter was not sent again", 1, letters.sent.size); assertEquals(listOf("a.pdf" to "scan of a.pdf"), letters.uploaded)
  }

  @Test
  fun `a file gone from the phone is a refusal, said with its name, and the letter stays sent`() = runTest {
    outbox.queue("Jane Smith", null, letter, listOf(staged("a.pdf")))
    outbox.items().first().single().payload.attachments.forEach { File(it.path).delete() }
    assertEquals(FlushOutcome(refused = 1), outbox.flush())
    val item = outbox.items().first().single()
    assertTrue(item.letterWasSent); assertEquals("The letter was sent, but a.pdf could not be attached: The file a.pdf could not be read back from this phone.", item.problem)
  }

  @Test
  fun `a refusal the app has no name for, 413 say, is a refusal to show, not something to retry for ever`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList()); outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2, body = "the one behind it"), emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Server(413, "Payload too large."))
    assertEquals(FlushOutcome(sent = 1, refused = 1), outbox.flush())
    assertEquals("the letter behind it went", listOf("the one behind it"), letters.sent.map { it.body })
    assertEquals("Payload too large.", outbox.items().first().first().problem)
  }

  @Test
  fun `a server fault is tried again, but not for ever, the fifth sets the letter aside with the fault's words and the letters behind it go`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList()); outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2, body = "the one behind it"), emptyList())
    repeat(4) {
      letters.sendResults += ApiResult.Failure(AppError.Server(503, "Service unavailable."))
      assertEquals("try $it: worth coming back for", FlushOutcome(stillWaiting = 2, tryAgain = true), outbox.flush())
    }
    assertTrue("nothing behind it has gone yet: order matters", letters.sent.isEmpty())
    letters.sendResults += ApiResult.Failure(AppError.Server(503, "Service unavailable."))
    assertEquals(FlushOutcome(sent = 1, refused = 1), outbox.flush())
    val item = outbox.items().first().first()
    assertEquals("Service unavailable.", item.problem)
    // "Try as it is" starts the count again.
    outbox.retry(item.id)
    letters.sendResults += ApiResult.Failure(AppError.Server(503, "Service unavailable."))
    assertEquals(FlushOutcome(stillWaiting = 1, tryAgain = true), outbox.flush())
  }

  @Test
  fun `signed out, or with the keys locked, nothing is retried, since the sign-in or the password books the run`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    letters.sendResults += ApiResult.Failure(AppError.Unauthorized(null))
    assertEquals(FlushOutcome(stillWaiting = 1), outbox.flush())
    letters.sendResults += ApiResult.Failure(lockedError(TestStrings()))
    assertEquals(FlushOutcome(stillWaiting = 1), outbox.flush())
  }

  @Test
  fun `a letter open for editing is not sent from the queue behind the writer's back, and goes back in line when they leave`() = runTest {
    val editing = outbox.queue("Jane Smith", null, letter, emptyList()); outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2, body = "the other one"), emptyList())
    outbox.open(editing)!!
    assertEquals("the one on the compose screen waits; the one behind it goes, and nothing is left that a retry would help", FlushOutcome(sent = 1), outbox.flush())
    assertEquals(listOf("the other one"), letters.sent.map { it.body })
    val before = scheduled
    outbox.release(editing)
    assertEquals("a send is booked for it again", before + 1, scheduled)
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals(listOf("the other one", letter.body), letters.sent.map { it.body })
  }

  @Test
  fun `a letter sent in an edited form while a flush was busy with an earlier one is not sent from the queue as well`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    val edited = outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2, body = "the one being edited"), emptyList())
    outbox.open(edited)!!
    // The flush has its list and is sending the first letter. Meanwhile the compose screen sends the edited letter
    // under a new key, removes the queued copy, and closes: the hold is gone before the flush reaches that row.
    letters.duringSend = { outbox.forget(edited); outbox.release(edited) }
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals("only the first letter went from the queue", listOf(letter.body), letters.sent.map { it.body })
  }

  @Test
  fun `a letter deleted while a flush was busy with an earlier one is not sent`() = runTest {
    outbox.queue("Jane Smith", null, letter, emptyList())
    val second = outbox.queue("Alex Johnson", null, letter.copy(prisonerId = 2, body = "thought better of it"), emptyList())
    letters.duringSend = { outbox.delete(second) }
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertEquals(listOf(letter.body), letters.sent.map { it.body })
  }

  @Test
  fun `an editor come back to holds the letter again if it is still queued, and is told when it is not`() = runTest {
    val id = outbox.queue("Jane Smith", null, letter, emptyList())
    outbox.open(id)!!; outbox.release(id)
    assertTrue(outbox.hold(id))
    assertEquals("held again", FlushOutcome(), outbox.flush())
    outbox.release(id)
    assertEquals(FlushOutcome(sent = 1), outbox.flush())
    assertFalse("it went while the editor was away", outbox.hold(id))
  }

  @Test
  fun `a letter the server already has cannot be reopened, because sending it from the compose screen would be a second letter`() = runTest {
    val id = outbox.queue("Jane Smith", null, letter, listOf(staged("scan.pdf")))
    letters.uploadResults += ApiResult.Failure(AppError.Validation(listOf("Unsupported file type.")))
    assertEquals(FlushOutcome(refused = 1), outbox.flush())
    assertNull(outbox.open(id))
  }

  // Fakes ---------------------------------------------------------------------------------------------

  class FakeOutboxDao : OutboxDao {
    val rows = MutableStateFlow<List<OutboxEntity>>(emptyList()); private var next = 1L
    override fun observe(userId: Int): Flow<List<OutboxEntity>> = rows.map { all -> all.filter { it.userId == userId }.sortedBy { it.queuedAt } }
    override suspend fun waiting(userId: Int) = rows.value.filter { it.userId == userId && it.state == OutboxEntity.STATE_WAITING }.sortedBy { it.id }
    override suspend fun countWaiting() = rows.value.count { it.state == OutboxEntity.STATE_WAITING }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insert(row: OutboxEntity): Long { val id = next++; rows.value = rows.value + row.copy(id = id); return id }
    override suspend fun update(row: OutboxEntity) { rows.value = rows.value.map { if (it.id == row.id) row else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun allFor(userId: Int) = rows.value.filter { it.userId == userId }
    override suspend fun deleteFor(userId: Int) { rows.value = rows.value.filterNot { it.userId == userId } }
    override suspend fun countAll() = rows.value.size
  }

  /** Answers from a script, then succeeds. Records what was really posted and uploaded. */
  class ScriptedLetters : LettersRepository {
    val sendResults = ArrayDeque<ApiResult<Letter>>(); val uploadResults = ArrayDeque<ApiResult<Attachment>>()
    val sent = mutableListOf<NewLetter>(); val uploaded = mutableListOf<Pair<String, String>>(); val uploadedTo = mutableListOf<Int>()
    val sendKeys = mutableListOf<String?>(); val uploadKeys = mutableListOf<String?>()
    /** Runs once, in the middle of the next send: what else happens on the phone while a flush is busy. */
    var duringSend: (suspend () -> Unit)? = null
    private fun stub(id: Int) = Letter(id, 41, 1, 2, false, LetterStatus.QUEUED, "x", null, null, null, false, null, null, emptyList(), emptyList())
    override suspend fun send(letter: NewLetter): ApiResult<Letter> { duringSend?.also { duringSend = null }?.invoke(); sendKeys += letter.idempotencyKey; sendResults.removeFirstOrNull()?.let { return it }; sent += letter; return ApiResult.Success(stub(99)) }
    override suspend fun upload(messageId: Int, staged: StagedFile, idempotencyKey: String?): ApiResult<Attachment> {
      uploadKeys += idempotencyKey
      uploadResults.removeFirstOrNull()?.let { return it }
      uploaded += staged.name to staged.file.readText(); uploadedTo += messageId
      return ApiResult.Success(Attachment(1, messageId, staged.name, staged.mimeType, staged.size))
    }
    override fun threads(): Flow<PagingData<Thread>> = emptyFlow()
    override suspend fun thread(chatId: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun threadForPrisoner(prisonerId: Int) = ApiResult.Success(null)
    override suspend fun letter(messageId: Int): ApiResult<Letter> = ApiResult.Success(stub(messageId))
    override suspend fun edit(edit: LetterEdit) = ApiResult.Success(Unit)
    override suspend fun delete(messageId: Int) = ApiResult.Success(Unit)
    override suspend fun deleteAttachment(attachmentId: Int) = ApiResult.Success(Unit)
    override suspend fun download(attachment: Attachment) = ApiResult.Success(File("x"))
    override suspend fun retentionDays() = ApiResult.Success(90)
  }

  class Files(private val dir: File) : LocalFilesContract {
    private var n = 0
    override fun newCameraTarget(): Pair<File, Uri> = error("not used")
    override fun stageCameraShot(file: File) = error("not used")
    override suspend fun stage(uri: Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) { staged.file.delete() }
    override fun downloadTarget(attachmentId: Int, name: String) = File(dir, name)
    override fun newOutboxFile() = File(dir, "outbox-${n++}.bin")
    override fun newStagingFile(name: String) = File(dir, "staging-${n++}-$name")
  }
}

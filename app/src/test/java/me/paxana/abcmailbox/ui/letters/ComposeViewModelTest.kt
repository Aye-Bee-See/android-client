package me.paxana.abcmailbox.ui.letters

import org.junit.Assert.assertNotNull
import me.paxana.abcmailbox.data.repo.FlushOutcome
import me.paxana.abcmailbox.data.repo.OutboxItem
import me.paxana.abcmailbox.data.repo.OutboxPayload
import me.paxana.abcmailbox.data.repo.OutboxRepository
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.repo.Draft
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.FacilityFilter
import me.paxana.abcmailbox.data.repo.GroupFilter
import me.paxana.abcmailbox.data.repo.LetterEdit
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.NewLetter
import me.paxana.abcmailbox.data.repo.PrisonerFilter
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.Attachment
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.MailRuleCatalog
import me.paxana.abcmailbox.domain.MailRules
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.RelayChoice
import me.paxana.abcmailbox.domain.Routing
import me.paxana.abcmailbox.domain.Thread
import me.paxana.abcmailbox.domain.Verification
import me.paxana.abcmailbox.ui.nav.ComposeRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private fun group(id: Int) = Group(id, "Group $id", "Town", null, null, null, null, emptyMap(), emptyList(), null, "relay", "active", emptyList(), emptyList(), null)
  private var rules = MailRules()
  private fun facility(routing: Routing, groups: List<Group>) = Facility(10, "Facility", emptyList(), null, routing, null, null, Verification(null, null), emptyList(), rules, groups)
  private fun prisoner() = Prisoner(3, "Alex", null, emptyList(), 10, null, null, null, null, null, null, null, null, emptyList(), null, null, null, null, null, false, Verification(null, null), emptyList())

  /** Records what compose hands to the outbox. */
  class FakeOutbox : OutboxRepository {
    val queued = mutableListOf<Triple<String, String?, NewLetter>>(); val forgotten = mutableListOf<Long>()
    var stored: Pair<OutboxPayload, List<StagedFile>>? = null
    override fun items(): Flow<List<OutboxItem>> = emptyFlow()
    override suspend fun queue(prisonerName: String, writingAs: String?, letter: NewLetter, attachments: List<StagedFile>): Long { queued += Triple(prisonerName, writingAs, letter); return 1 }
    override suspend fun open(id: Long) = stored
    override suspend fun delete(id: Long) { forgotten += id }
    override suspend fun retry(id: Long) = Unit
    override suspend fun flush() = FlushOutcome()
    override suspend fun hasWaiting() = queued.isNotEmpty()
  }
  private val outbox = FakeOutbox()

  private fun vm(routing: Routing, groups: List<Group>, letters: FakeLetters = FakeLetters(), drafts: FakeDrafts = FakeDrafts(), edit: Int? = null,
                 route: ComposeRoute = ComposeRoute(prisonerId = 3, editMessageId = edit), session: SessionRepository = FakeSession()) =
    ComposeViewModel(letters, FakeDirectory(prisoner(), facility(routing, groups)), drafts, FakeLocalFiles(), session, route, outbox)

  @Test
  fun `one relay group is automatic and sent explicitly`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.DIRECT, listOf(group(7)), letters)
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.relay is RelayChoice.Automatic)
    vm.onBodyChange("Dear Alex"); vm.send()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(NewLetter(3, "Dear Alex", null, 7), letters.sent.single())
    assertEquals(41, vm.ui.value.sentChatId)
  }

  @Test
  fun `relay-only with two groups blocks sending until one is chosen`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.RELAY_ONLY, listOf(group(1), group(2)), letters)
    dispatcher.scheduler.advanceUntilIdle()
    vm.onBodyChange("Hello")
    assertFalse(vm.ui.value.canSend)
    vm.onSelectRelay(2)
    assertTrue(vm.ui.value.canSend)
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(2, letters.sent.single().relayChapter)
  }

  @Test
  fun `relay-only with no group is blocked outright`() = runTest {
    val vm = vm(Routing.RELAY_ONLY, emptyList())
    dispatcher.scheduler.advanceUntilIdle()
    vm.onBodyChange("Hello")
    assertTrue(vm.ui.value.relay is RelayChoice.Blocked)
    assertFalse(vm.ui.value.canSend)
  }

  @Test
  fun `draft is restored, autosaved after a pause, and deleted on send`() = runTest {
    val drafts = FakeDrafts(mutableMapOf((1 to 3) to Draft("half written", "note", null, 0)))
    val vm = vm(Routing.DIRECT, emptyList(), drafts = drafts)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("half written", vm.ui.value.body)
    assertTrue(vm.ui.value.draftRestored)
    vm.onBodyChange("half written, more")
    dispatcher.scheduler.advanceTimeBy(700); dispatcher.scheduler.runCurrent()
    assertEquals("half written, more", drafts.store[1 to 3]?.body)
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertNull(drafts.store[1 to 3])
  }

  @Test
  fun `a failed send keeps the text and shows the server sentence`() = runTest {
    val letters = FakeLetters(fail = AppError.Validation(listOf("Choose a relay group.")))
    val vm = vm(Routing.DIRECT, emptyList(), letters)
    dispatcher.scheduler.advanceUntilIdle()
    vm.onBodyChange("Hello"); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Choose a relay group.", vm.ui.value.error)
    assertEquals("Hello", vm.ui.value.body)
    assertNull(vm.ui.value.sentChatId)
  }

  @Test
  fun `a facility that refuses pictures only offers PDF attachments and says why`() = runTest {
    rules = MailRules(rules = MailRuleCatalog.Compiled.resolveAll(listOf("no_photos")), pageLimit = 1)
    val vm = vm(Routing.DIRECT, emptyList())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("application/pdf"), vm.ui.value.allowedAttachmentTypes.toList())
    assertTrue(vm.ui.value.advice.any { it.text.contains("refuses pictures") })
    assertFalse("one page is within the limit", vm.ui.value.advice.any { it.warning })
    vm.onBodyChange("x".repeat(3500))
    assertTrue("two pages against a limit of one", vm.ui.value.advice.any { it.warning && it.text.contains("at most 1") })
    assertTrue("advice never blocks sending", vm.ui.value.canSend)
  }

  @Test
  fun `a group writes as a managed writer, and never drafts someone else's letter on this phone`() = runTest {
    val letters = FakeLetters(); val drafts = FakeDrafts()
    val vm = vm(Routing.DIRECT, listOf(group(7)), letters, drafts, route = ComposeRoute(3, writerId = 44, writerName = "Maria T."), session = FakeSession(role = "chapter", chapterId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Maria T.", vm.ui.value.writingAs)
    vm.onBodyChange("Written at letter night"); dispatcher.scheduler.advanceTimeBy(700); dispatcher.scheduler.runCurrent()
    assertTrue("no draft for a letter written on someone's behalf", drafts.store.isEmpty())
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    // The member's group relays for this facility, which on an end-to-end server is what lets it hold an envelope.
    assertEquals(NewLetter(3, "Written at letter night", null, 7, asWriterId = 44, fromPrisoner = false, groupRelaysFacility = true), letters.sent.single())
  }

  @Test
  fun `a group with no writer chosen sends as its anonymous writer`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.DIRECT, emptyList(), letters, session = FakeSession(role = "chapter", chapterId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Anonymous writer", vm.ui.value.writingAs)
    vm.onBodyChange("From a friend"); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertNull(letters.sent.single().asWriterId)
  }

  @Test
  fun `recording a reply ignores routing, is from the prisoner, and lands on the writer's thread`() = runTest {
    val letters = FakeLetters()
    // Relay-only with no group would block a letter; a reply is not mailed anywhere, so it must not be blocked.
    val vm = vm(Routing.RELAY_ONLY, emptyList(), letters, route = ComposeRoute(3, replyForUserId = 4), session = FakeSession(role = "chapter", chapterId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.recordingReply)
    vm.onBodyChange("Dear friend, thank you.")
    assertTrue(vm.ui.value.canSend)
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    val sent = letters.sent.single()
    assertTrue(sent.fromPrisoner); assertEquals(4, sent.asWriterId); assertNull(sent.relayChapter)
  }

  @Test
  fun `edit mode loads the letter and saves through edit`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.DIRECT, emptyList(), letters, edit = 41)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("probe", vm.ui.value.body)
    vm.onBodyChange("probe, edited"); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(LetterEdit(41, "probe, edited", null, null), letters.edits.single())
    assertEquals(41, vm.ui.value.sentChatId)
  }

  // Fakes ---------------------------------------------------------------

  @Test
  fun `with no route to the server the letter goes to the outbox, the draft is cleared, and the screen closes`() = runTest {
    val drafts = FakeDrafts()
    val model = vm(Routing.DIRECT, emptyList(), letters = FakeLetters(fail = AppError.Network(java.net.UnknownHostException())), drafts = drafts)
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Written in the basement"); dispatcher.scheduler.advanceUntilIdle()
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    val (name, writingAs, letter) = outbox.queued.single()
    assertEquals("Alex", name); assertNull(writingAs); assertEquals("Written in the basement", letter.body)
    assertTrue(model.ui.value.queuedOffline); assertNull(model.ui.value.error)
    assertTrue("the outbox has it now; a draft would offer it a second time", drafts.store.isEmpty())
  }

  @Test
  fun `a timeout is not queued, because the letter may have arrived, and the server's no is shown at once`() = runTest {
    for (error in listOf(AppError.Network(java.net.SocketTimeoutException()), AppError.Forbidden("Your account cannot write to this prisoner."))) {
      val model = vm(Routing.DIRECT, emptyList(), letters = FakeLetters(fail = error))
      dispatcher.scheduler.advanceUntilIdle()
      model.onBodyChange("Hello"); model.send(); dispatcher.scheduler.advanceUntilIdle()
      assertTrue(outbox.queued.isEmpty()); assertFalse(model.ui.value.queuedOffline)
      assertNotNull(model.ui.value.error)
    }
  }

  @Test
  fun `a letter reopened from the outbox starts from its text, and sending it removes the queued copy`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", relayNote = "blue paper") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Queued last night", model.ui.value.body); assertEquals("blue paper", model.ui.value.note)
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Queued last night", letters.sent.single().body)
    assertEquals(listOf(7L), outbox.forgotten)
  }

  class FakeLetters(private val fail: AppError? = null) : LettersRepository {
    val sent = mutableListOf<NewLetter>()
    val edits = mutableListOf<LetterEdit>()
    private fun stub(id: Int) = Letter(id, 41, 3, 1, false, LetterStatus.QUEUED, "probe", null, null, null, false, null, null, emptyList(), emptyList())
    override fun threads(): Flow<PagingData<Thread>> = emptyFlow()
    override suspend fun thread(chatId: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun threadForPrisoner(prisonerId: Int) = ApiResult.Success(null)
    override suspend fun letter(messageId: Int): ApiResult<Letter> = ApiResult.Success(stub(messageId))
    override suspend fun send(letter: NewLetter): ApiResult<Letter> { fail?.let { return ApiResult.Failure(it) }; sent += letter; return ApiResult.Success(stub(99)) }
    override suspend fun edit(edit: LetterEdit): ApiResult<Unit> { edits += edit; return ApiResult.Success(Unit) }
    override suspend fun delete(messageId: Int) = ApiResult.Success(Unit)
    override suspend fun upload(messageId: Int, staged: StagedFile) = ApiResult.Success(Attachment(1, messageId, staged.name, staged.mimeType, staged.size))
    override suspend fun deleteAttachment(attachmentId: Int) = ApiResult.Success(Unit)
    override suspend fun download(attachment: Attachment) = ApiResult.Success(File("x"))
    override suspend fun retentionDays() = ApiResult.Success(90)
  }

  class FakeDirectory(private val p: Prisoner, private val f: Facility) : DirectoryRepository {
    override fun prisoners(filter: PrisonerFilter): Flow<PagingData<Prisoner>> = emptyFlow()
    override fun facilities(filter: FacilityFilter): Flow<PagingData<Facility>> = emptyFlow()
    override fun groups(filter: GroupFilter): Flow<PagingData<Group>> = emptyFlow()
    override suspend fun featuredPrisoners(limit: Int) = ApiResult.Success(listOf(p))
    override suspend fun prisoner(id: Int) = ApiResult.Success(p)
    override suspend fun facility(id: Int) = ApiResult.Success(f)
    override suspend fun group(id: Int) = ApiResult.Failure(AppError.NotFound(null))
  }

  class FakeDrafts(val store: MutableMap<Pair<Int, Int>, Draft> = mutableMapOf()) : DraftsRepository {
    override suspend fun load(userId: Int, prisonerId: Int) = store[userId to prisonerId]
    override suspend fun save(userId: Int, prisonerId: Int, draft: Draft) { store[userId to prisonerId] = draft }
    override suspend fun delete(userId: Int, prisonerId: Int) { store.remove(userId to prisonerId) }
  }

  class FakeSession(role: String = "user", chapterId: Int? = null) : SessionRepository {
    override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.SignedIn(Session("t", 0, SessionUser(1, "user1", null, null, role, chapterId))))
    override suspend fun login(username: String, password: String) = ApiResult.Failure(AppError.Unauthorized(null))
    override suspend fun logout(everywhere: Boolean) = ApiResult.Success(Unit)
    override val expired = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    override suspend fun claimInfo(token: String) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun claim(token: String, username: String, password: String, email: String?) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun changePassword(current: String, new: String) = ApiResult.Success(Unit)
    override val pendingRecoveryCode = MutableStateFlow<String?>(null)
    override fun recoveryCodeSaved() = Unit
    override val keysLocked = MutableStateFlow(false)
    override suspend fun unlock(password: String) = ApiResult.Success(Unit)
    override suspend fun recover(username: String, recoveryCode: String, newPassword: String) = ApiResult.Failure(AppError.NotFound(null))
  }

  class FakeLocalFiles : me.paxana.abcmailbox.data.files.LocalFilesContract {
    override fun newCameraTarget(): Pair<File, android.net.Uri> = error("not used")
    override fun stageCameraShot(file: File): StagedFile = StagedFile(file, file.name, "image/jpeg", 3)
    override suspend fun stage(uri: android.net.Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) = Unit
    override fun downloadTarget(attachmentId: Int, name: String) = File("x")
  }
}

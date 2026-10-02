package me.paxana.abcmailbox.ui.letters

import me.paxana.abcmailbox.text.TestStrings
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
    val released = mutableListOf<Long>()
    override fun release(id: Long) { released += id }
    val heldAgain = mutableListOf<Long>(); var stillQueued = true
    override suspend fun hold(id: Long): Boolean { heldAgain += id; return stillQueued }
    override suspend fun retry(id: Long) = Unit
    override suspend fun flush() = FlushOutcome()
    override suspend fun hasWaiting() = queued.isNotEmpty()
    override val limitedUntil = kotlinx.coroutines.flow.MutableStateFlow<java.time.Instant?>(null)
    val waits = mutableListOf<Long?>()
    override fun waitOut(retryAfterSeconds: Long?) { waits += retryAfterSeconds; limitedUntil.value = java.time.Instant.parse("2026-09-27T20:45:00Z") }
  }
  private val outbox = FakeOutbox()

  private fun vm(routing: Routing, groups: List<Group>, letters: FakeLetters = FakeLetters(), drafts: FakeDrafts = FakeDrafts(), edit: Int? = null,
                 route: ComposeRoute = ComposeRoute(prisonerId = 3, editMessageId = edit), session: SessionRepository = FakeSession()) =
    ComposeViewModel(letters, FakeDirectory(prisoner(), facility(routing, groups)), drafts, files, session, route, outbox, TestStrings())
  private val files = FakeLocalFiles()
  /** What the navigation back stack does when the screen is left: `onCleared` is protected. */
  private fun ComposeViewModel.leave() = ComposeViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)

  @Test
  fun `one relay group is automatic and sent explicitly`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.DIRECT, listOf(group(7)), letters)
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.relay is RelayChoice.Automatic)
    vm.onBodyChange("Dear Alex"); vm.send()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(NewLetter(3, "Dear Alex", null, 7), letters.sent.single().copy(idempotencyKey = null))
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
  fun `sending a returned letter again starts from its words, names it, and leaves the ordinary draft alone`() = runTest {
    val letters = FakeLetters(); val drafts = FakeDrafts(mutableMapOf((1 to 3) to Draft("a different, unfinished letter", null, null, 0)))
    val vm = vm(Routing.DIRECT, listOf(group(1)), letters, drafts, route = ComposeRoute(prisonerId = 3, resendOf = 41))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("the returned letter's text, not the draft's", "probe", vm.ui.value.body)
    assertTrue(vm.ui.value.sendingAgain); assertFalse(vm.ui.value.draftRestored)
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(41, letters.sent.single().resendOf); assertEquals(null, letters.sent.single().replacesHeld)
    assertEquals("who mails it is decided afresh, from where they are now", 1, letters.sent.single().relayChapter)
    assertEquals("the unfinished letter is still there", "a different, unfinished letter", drafts.store[1 to 3]?.body)
  }

  @Test
  fun `a held letter that must be sealed again is sent as a new one that replaces it`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.DIRECT, listOf(group(1)), letters, route = ComposeRoute(prisonerId = 3, replacesHeld = 52))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("probe", vm.ui.value.body)
    vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(52, letters.sent.single().replacesHeld); assertEquals(null, letters.sent.single().resendOf)
  }

  @Test
  fun `choosing who mails a held letter does not quietly keep the group that no longer serves them`() = runTest {
    // The letter says group 7, from before the move. The new facility is relay-only and offers 1 and 2.
    val letters = FakeLetters(relayGroupOfStub = 7)
    val vm = vm(Routing.RELAY_ONLY, listOf(group(1), group(2)), letters, edit = 52)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(null, vm.ui.value.selectedRelay); assertFalse("nothing to save until a group is chosen", vm.ui.value.canSend)
    vm.onSelectRelay(2); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("the choice is what lifts the hold", 2, letters.edits.single().relayChapter)
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
    assertTrue(vm.ui.value.advice(TestStrings()).any { it.text.contains("refuses pictures") })
    assertFalse("one page is within the limit", vm.ui.value.advice(TestStrings()).any { it.warning })
    vm.onBodyChange("x".repeat(3500))
    assertTrue("two pages against a limit of one", vm.ui.value.advice(TestStrings()).any { it.warning && it.text.contains("at most 1") })
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
    assertEquals(NewLetter(3, "Written at letter night", null, 7, asWriterId = 44, fromPrisoner = false, groupRelaysFacility = true), letters.sent.single().copy(idempotencyKey = null))
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
    assertTrue(sent.fromPrisoner); assertEquals(4, sent.asWriterId); assertNull(sent.relayChapter); assertNull(sent.reference)
  }

  @Test
  fun `a reply filed by its reference carries the number, so the server names the letter it answers`() = runTest {
    val letters = FakeLetters()
    val vm = vm(Routing.RELAY_ONLY, emptyList(), letters, route = ComposeRoute(3, replyForUserId = 4, reference = "4827-1935-6"), session = FakeSession(role = "chapter", chapterId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    vm.onBodyChange("Dear friend, thank you."); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    val sent = letters.sent.single()
    assertTrue(sent.fromPrisoner); assertEquals(4, sent.asWriterId); assertEquals("4827-1935-6", sent.reference)
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
  fun `a timeout is queued too, under the key the first try used, so the server can recognise the letter if it arrived`() = runTest {
    val letters = FakeLetters(fail = AppError.Network(java.net.SocketTimeoutException()))
    val model = vm(Routing.DIRECT, emptyList(), letters = letters)
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Hello"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    val queued = outbox.queued.single().third
    assertTrue(model.ui.value.queuedOffline)
    assertEquals("the key that went to the server is the key that was queued", letters.triedKeys.single(), queued.idempotencyKey)
  }

  @Test
  fun `a 429 queues the letter to go by itself when the wait is over, under the key the first try used`() = runTest {
    val letters = FakeLetters(fail = AppError.RateLimited("Too many letters and replies. Try again in 45 minute(s).", 2700))
    val model = vm(Routing.DIRECT, emptyList(), letters = letters)
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Hello"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    val queued = outbox.queued.single().third
    assertEquals("the outbox waits out the server's Retry-After", listOf<Long?>(2700), outbox.waits)
    assertTrue(model.ui.value.queuedOffline); assertNull(model.ui.value.error)
    assertEquals("the screen can say when it goes", java.time.Instant.parse("2026-09-27T20:45:00Z"), model.ui.value.queuedLimitedUntil)
    assertEquals(letters.triedKeys.single(), queued.idempotencyKey)
  }

  @Test
  fun `the server's no is shown at once and nothing is queued, and the same words keep the same key across tries`() = runTest {
    val letters = FakeLetters(fail = AppError.Forbidden("Your account cannot write to this prisoner."))
    val model = vm(Routing.DIRECT, emptyList(), letters = letters)
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Hello"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(outbox.queued.isEmpty()); assertFalse(model.ui.value.queuedOffline)
    assertEquals("Your account cannot write to this prisoner.", model.ui.value.error)

    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("a second press is the same letter", 1, letters.triedKeys.toSet().size)
    model.onBodyChange("Hello again"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("different words are a different letter", 2, letters.triedKeys.toSet().size)
  }

  @Test
  fun `a letter reopened from the outbox starts from its text, and sending it removes the queued copy`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", relayNote = "blue paper", idempotencyKey = "key-of-the-queued-copy") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Queued last night", model.ui.value.body); assertEquals("blue paper", model.ui.value.note)
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Queued last night", letters.sent.single().body)
    assertEquals(listOf(7L), outbox.forgotten)
  }

  @Test
  fun `a reopened letter sent as it was goes under the key it was queued with, because the earlier try may have arrived`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", idempotencyKey = "key-of-the-queued-copy") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf<String?>("key-of-the-queued-copy"), letters.triedKeys)
  }

  @Test
  fun `a reopened letter whose words were changed is a different letter, with a key of its own`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", idempotencyKey = "key-of-the-queued-copy") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Queued last night, and one more thing"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(letters.triedKeys.single() == "key-of-the-queued-copy")
  }

  @Test
  fun `a queued send-again opened for editing still names the letter it replaces`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Second try", idempotencyKey = "k", resendOf = 41, replacesHeld = 52) to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.onBodyChange("Second try, reworded"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(41, letters.sent.single().resendOf); assertEquals(52, letters.sent.single().replacesHeld)
  }

  @Test
  fun `leaving a reopened letter without sending puts it back in line and throws the plain copies of its files away`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", idempotencyKey = "k") to listOf(StagedFile(File("scan.pdf"), "scan.pdf", "application/pdf", 3))
    val model = vm(Routing.DIRECT, emptyList(), route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.leave()
    assertEquals(listOf(7L), outbox.released); assertEquals(listOf("scan.pdf"), files.discarded)
    assertTrue("it was not sent, so it is not removed", outbox.forgotten.isEmpty())
  }

  @Test
  fun `a queued letter that went while Edit was being tapped is not offered for editing, and its row is left alone`() = runTest {
    outbox.stored = null
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That letter is no longer waiting on this phone. Look for it in the conversation before writing it again.", model.ui.value.error)
    assertEquals("", model.ui.value.body)
    model.onBodyChange("Something new"); assertFalse("there is nothing to edit here", model.ui.value.canSend)
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(letters.sent.isEmpty()); assertTrue("what is under that id now is not this screen's to delete", outbox.forgotten.isEmpty())
  }

  @Test
  fun `a queued letter is held back only while its editor is on show, and an editor come back to after the letter went sends nothing`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", idempotencyKey = "k") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.onShown(); assertTrue("first shown: open() already holds it", outbox.heldAgain.isEmpty())

    // Another bottom tab: the screen and this ViewModel are kept, never cleared. The letter goes back in line.
    model.onHidden(); assertEquals(listOf(7L), outbox.released)
    // Back, and it is still queued: held again, and the editor works as before.
    model.onShown(); assertFalse("not until the outbox has answered", model.ui.value.canSend)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(7L), outbox.heldAgain); assertTrue(model.ui.value.canSend)

    // Away again, and this time the outbox sent it meanwhile.
    model.onHidden(); outbox.stillQueued = false
    model.onShown(); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(model.ui.value.canSend)
    assertEquals("That letter is no longer waiting on this phone. Look for it in the conversation before writing it again.", model.ui.value.error)
    model.onBodyChange("Queued last night, and more"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("a changed copy of a letter that went would be a second letter", letters.sent.isEmpty()); assertTrue(outbox.forgotten.isEmpty())
  }

  @Test
  fun `changing only the note or the relay group keeps the key, and a letter an earlier try already made is edited to match`() = runTest {
    // Queued with one note; the earlier try had arrived unheard. The server tells letters apart by sender, prisoner and
    // text, so under the same key it answers with that letter (the fake's stub: no note).
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", relayNote = "blue paper", idempotencyKey = "key-of-the-queued-copy") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.onNoteChange("green paper"); model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("a new key would have made a second letter", listOf<String?>("key-of-the-queued-copy"), letters.triedKeys)
    assertEquals("the one letter is brought in line", listOf("green paper"), letters.edits.map { it.relayNote }); assertEquals(99, letters.edits.single().messageId)
  }

  @Test
  fun `a letter sent as it was queued is not edited afterwards`() = runTest {
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "Queued last night", relayNote = "blue paper", idempotencyKey = "k") to emptyList()
    val letters = FakeLetters()
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, letters.sent.size); assertTrue(letters.edits.isEmpty())
  }

  @Test
  fun `a file that did not upload does not keep the screen open, where Send would make a second letter`() = runTest {
    val two = listOf(StagedFile(File("a.pdf"), "a.pdf", "application/pdf", 3), StagedFile(File("b.pdf"), "b.pdf", "application/pdf", 3))
    outbox.stored = OutboxPayload(prisonerId = 3, prisonerName = "Alex", body = "With two scans", idempotencyKey = "k") to two
    val letters = FakeLetters(failUploads = setOf("b.pdf"))
    val model = vm(Routing.DIRECT, emptyList(), letters = letters, route = ComposeRoute(prisonerId = 3, outboxId = 7))
    dispatcher.scheduler.advanceUntilIdle()
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    val ui = model.ui.value
    assertTrue(ui.sent); assertEquals(41, ui.sentChatId); assertNull(ui.error)
    assertEquals("The letter was sent, but these files did not upload: b.pdf.", ui.sentNotice)
    assertFalse("the letter exists: there is nothing left here to send", ui.canSend)
    assertTrue(ui.attachments.isEmpty()); assertEquals(listOf("a.pdf", "b.pdf"), files.discarded)
    model.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("one letter, however often Send is pressed", 1, letters.sent.size)
  }

  class FakeLetters(private val fail: AppError? = null, /** The relay group the stub letter says it has. */ private val relayGroupOfStub: Int? = null, /** Files, by name, whose upload fails. */ private val failUploads: Set<String> = emptySet()) : LettersRepository {
    val sent = mutableListOf<NewLetter>(); val triedKeys = mutableListOf<String?>()
    val edits = mutableListOf<LetterEdit>()
    private fun stub(id: Int) = Letter(id, 41, 3, 1, false, LetterStatus.QUEUED, "probe", null, relayGroupOfStub, null, false, null, null, emptyList(), emptyList())
    override fun threads(): Flow<PagingData<Thread>> = emptyFlow()
    override suspend fun thread(chatId: Int) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun threadForPrisoner(prisonerId: Int) = ApiResult.Success(null)
    override suspend fun letter(messageId: Int): ApiResult<Letter> = ApiResult.Success(stub(messageId))
    override suspend fun send(letter: NewLetter): ApiResult<Letter> { triedKeys += letter.idempotencyKey; fail?.let { return ApiResult.Failure(it) }; sent += letter; return ApiResult.Success(stub(99)) }
    override suspend fun edit(edit: LetterEdit): ApiResult<Unit> { edits += edit; return ApiResult.Success(Unit) }
    override suspend fun delete(messageId: Int) = ApiResult.Success(Unit)
    override suspend fun upload(messageId: Int, staged: StagedFile, idempotencyKey: String?): ApiResult<Attachment> =
      if (staged.name in failUploads) ApiResult.Failure(AppError.Network(java.net.SocketTimeoutException())) else ApiResult.Success(Attachment(1, messageId, staged.name, staged.mimeType, staged.size))
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
    override suspend fun login(username: String, password: String, olderAccount: Boolean) = ApiResult.Failure(AppError.Unauthorized(null))
    override suspend fun logout(everywhere: Boolean) = ApiResult.Success(Unit)
    override val expired = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    override suspend fun claimInfo(token: String) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun claim(token: String, username: String, password: String, email: String?, penName: String?) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun joinInfo(code: String) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun join(code: String, username: String, password: String, email: String?, name: String?, penName: String?) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun invitationInfo(token: String) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun acceptInvitation(token: String, username: String, password: String, email: String, name: String?, group: me.paxana.abcmailbox.domain.NewGroupProfile?, groupFields: Set<String>, penName: String?) = ApiResult.Failure(AppError.NotFound(null))
    override suspend fun changePassword(current: String, new: String) = ApiResult.Success(Unit)
    override suspend fun deleteAccount(password: String, wipe: suspend (userId: Int) -> Unit): ApiResult<me.paxana.abcmailbox.data.api.DeletionReportDto> = error("not used")
    override val pendingRecoveryCode = MutableStateFlow<String?>(null)
    override suspend fun recoveryCodeSaved(): ApiResult<Int> = ApiResult.Success(0)
    override suspend fun passwordStaysOnPhone() = true
    override val keysLocked = MutableStateFlow(false)
    override suspend fun unlock(password: String) = ApiResult.Success(Unit)
    override suspend fun recover(username: String, recoveryCode: String, newPassword: String) = ApiResult.Failure(AppError.NotFound(null))
  }

  class FakeLocalFiles : me.paxana.abcmailbox.data.files.LocalFilesContract {
    val discarded = mutableListOf<String>()
    override fun newCameraTarget(): Pair<File, android.net.Uri> = error("not used")
    override fun stageCameraShot(file: File): StagedFile = StagedFile(file, file.name, "image/jpeg", 3)
    override suspend fun stage(uri: android.net.Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) { discarded += staged.name }
    override fun downloadTarget(attachmentId: Int, name: String) = File("x")
  }

  @Test
  fun `refused because the chosen group blocked the writer, the choice is offered again without it, and with no other group the letter stays put (API 171)`() = runTest {
    val blocked = AppError.Forbidden("Error creating message.", "GroupBlockError", "group_block")
    val vm = vm(Routing.RELAY_ONLY, listOf(group(1), group(2)), FakeLetters(fail = blocked))
    dispatcher.scheduler.advanceUntilIdle()
    vm.onBodyChange("Hello"); vm.onSelectRelay(2); vm.send(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(1), (vm.ui.value.relay as RelayChoice.Choose).options.map { it.id })
    assertNull(vm.ui.value.selectedRelay); assertFalse(vm.ui.value.canSend)
    assertEquals("Group 2 is not mailing letters from your account. Another group mails to this facility: choose it below, then send.", vm.ui.value.error)
    assertEquals("the letter is still on the screen", "Hello", vm.ui.value.body)

    val only = vm(Routing.RELAY_ONLY, listOf(group(1)), FakeLetters(fail = blocked))
    dispatcher.scheduler.advanceUntilIdle()
    only.onBodyChange("Hello"); only.send(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(only.ui.value.error!!.startsWith("Group 1 is not mailing letters from your account, and no other group mails to this facility"))
  }
}

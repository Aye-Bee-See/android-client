package me.paxana.abcmailbox.ui.group

import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.data.repo.ReturnedAs
import me.paxana.abcmailbox.domain.HeldReason
import me.paxana.abcmailbox.domain.ReturnReason
import me.paxana.abcmailbox.text.TestStrings
import kotlinx.coroutines.flow.MutableStateFlow
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.crypto.GroupKey
import me.paxana.abcmailbox.data.crypto.GroupKeyState
import me.paxana.abcmailbox.domain.GroupMember
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.domain.IssuedToken
import me.paxana.abcmailbox.domain.Letter
import me.paxana.abcmailbox.domain.LetterStatus
import me.paxana.abcmailbox.domain.ManagedWriter
import me.paxana.abcmailbox.domain.QueueItem
import me.paxana.abcmailbox.ui.directory.Loadable
import me.paxana.abcmailbox.ui.letters.ComposeViewModelTest
import me.paxana.abcmailbox.ui.nav.HandoffRoute
import me.paxana.abcmailbox.ui.nav.LetterWorkRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelsTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private fun letter(status: LetterStatus) = Letter(41, 41, 1, 4, false, status, "Dear Jane", "two pages", 1, "Test Chapter", false, null, null, emptyList(), emptyList())

  class FakeGroup(var status: LetterStatus = LetterStatus.QUEUED, private val refuse: AppError? = null) : GroupRepository {
    val moves = mutableListOf<LetterStatus>(); var issued = 0; var revoked = 0
    private fun letter() = Letter(41, 41, 1, 4, false, status, "Dear Jane", null, 1, "Test Chapter", false, null, null, emptyList(), emptyList())
    override fun queue(groupId: Int, status: LetterStatus): Flow<PagingData<QueueItem>> = emptyFlow()
    var looks = 0
    // Letter nights (API PR #111) and the group's numbers (PR #112).
    val batches = mutableListOf<Pair<List<Int>, LetterStatus>>(); var batchRefusal: AppError? = null; val batchDeclined = mutableListOf<me.paxana.abcmailbox.data.repo.DeclinedAs?>()
    override suspend fun setStatusOfMany(messageIds: List<Int>, status: LetterStatus, declined: me.paxana.abcmailbox.data.repo.DeclinedAs?): ApiResult<Int> { batchRefusal?.let { return ApiResult.Failure(it) }; batches += messageIds to status; batchDeclined += declined; return ApiResult.Success(messageIds.size) }
    var numbers: me.paxana.abcmailbox.data.repo.GroupNumbers? = me.paxana.abcmailbox.data.repo.GroupNumbers("Test Chapter", before = 0, countedHere = 1, published = null, averageDaysToMail = null)
    val savedBefore = mutableListOf<Int>()
    override suspend fun numbers() = ApiResult.Success(numbers)
    override suspend fun setLettersSentBefore(count: Int): ApiResult<Unit> { refuse?.let { return ApiResult.Failure(it) }; savedBefore += count; numbers = numbers?.copy(before = count, published = (count + 1).takeIf { it >= 20 }?.toString()); return ApiResult.Success(Unit) }
    /** With a full read the letter carries its footer and reference (API PR #120); a status answer does not. */
    val footer = me.paxana.abcmailbox.domain.LetterFooter("Sam Hollow", false, 1, "Test Chapter", "5476-3594-6", false)
    override suspend fun queueItem(messageId: Int): ApiResult<QueueItem> { looks++; return ApiResult.Success(QueueItem(letter().copy(footer = footer, replyReference = "5476-3594-6"), null)) }
    /** What came with each move: how it came back, and whether a hold was knowingly released. */
    val returnedAs = mutableListOf<me.paxana.abcmailbox.data.repo.ReturnedAs?>(); val releases = mutableListOf<Boolean>(); val declinedAs = mutableListOf<me.paxana.abcmailbox.data.repo.DeclinedAs?>()
    /** Set to make the letter held, as the server would after someone is freed. */
    var held: me.paxana.abcmailbox.domain.HeldReason? = null
    override suspend fun setStatus(messageId: Int, status: LetterStatus, returned: me.paxana.abcmailbox.data.repo.ReturnedAs?, release: Boolean, declined: me.paxana.abcmailbox.data.repo.DeclinedAs?): ApiResult<Letter> {
      refuse?.let { return ApiResult.Failure(it) }
      if (held != null && status == LetterStatus.PRINTED && !release) return ApiResult.Failure(AppError.Conflict("This letter is held.", "LetterHeldError"))
      moves += status; returnedAs += returned; releases += release; declinedAs += declined; this.status = status; held = null
      return ApiResult.Success(letter().copy(declineReason = declined?.reason, declineRule = declined?.rule, declineNote = declined?.note))
    }
    override suspend fun writers() = ApiResult.Success(emptyList<ManagedWriter>())
    override suspend fun addWriter(name: String, email: String?, note: String?): ApiResult<ManagedWriter> = refuse?.let { ApiResult.Failure(it) } ?: ApiResult.Success(ManagedWriter(47, name.trim(), email, note, null))
    override suspend fun issueToken(writerId: Int) = ApiResult.Success(IssuedToken("TOKEN${++issued}", null))
    override suspend fun revokeToken(writerId: Int): ApiResult<Unit> { revoked++; return ApiResult.Success(Unit) }

    // The group key: a test sets the state and the members, and reads back what was asked for.
    override val keyState = MutableStateFlow<GroupKeyState>(GroupKeyState.NotNeeded)
    var team = listOf(GroupMember(9, "Sam", hasOwnKey = true, holdsGroupKey = true, isMe = true), GroupMember(10, "Noor", hasOwnKey = true, holdsGroupKey = false, isMe = false))
    val handed = mutableListOf<Int>(); val stopped = mutableListOf<Int>(); val shared = mutableListOf<Pair<Int, Int>>()
    var partners = emptyList<me.paxana.abcmailbox.domain.Group>()
    override suspend fun refreshKeyState() = keyState.value
    override suspend fun setUpGroupKey(): ApiResult<Unit> { refuse?.let { return ApiResult.Failure(it) }; keyState.value = GroupKeyState.Ready(GroupKey(1, Sodium.KeyPair(ByteArray(1), ByteArray(1)), "PUB", 1)); return ApiResult.Success(Unit) }
    override suspend fun members() = ApiResult.Success(team)
    override suspend fun handKeyTo(memberId: Int): ApiResult<Unit> {
      refuse?.let { return ApiResult.Failure(it) }
      handed += memberId; team = team.map { if (it.id == memberId) it.copy(holdsGroupKey = true) else it }; return ApiResult.Success(Unit)
    }
    override suspend fun stopHandingKeyTo(memberId: Int): ApiResult<Unit> { stopped += memberId; team = team.map { if (it.id == memberId) it.copy(holdsGroupKey = false) else it }; return ApiResult.Success(Unit) }
    override suspend fun partnersFor(prisonerId: Int) = partners
    override suspend fun shareWith(messageId: Int, partnerGroupId: Int): ApiResult<Unit> { refuse?.let { return ApiResult.Failure(it) }; shared += messageId to partnerGroupId; return ApiResult.Success(Unit) }
  }

  @Test
  fun `setting up the group key opens it, and a refusal is shown in the API's words`() = runTest {
    val group = FakeGroup(); val vm = GroupKeyViewModel(group, TestStrings())
    vm.setUp(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.keyState.value is GroupKeyState.Ready)
    assertTrue(vm.ui.value.notice!!.contains("Hand it to the other group admins"))

    val late = GroupKeyViewModel(FakeGroup(refuse = AppError.Conflict("This group already has keys.")), TestStrings())
    late.setUp(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("This group already has keys.", late.ui.value.error)
    assertFalse(late.ui.value.busy)
  }

  @Test
  fun `handing the key to a member reloads the list, which then shows them as a holder`() = runTest {
    val group = FakeGroup(); val vm = GroupKeyViewModel(group, TestStrings())
    vm.loadMembers(); dispatcher.scheduler.advanceUntilIdle()
    val noor = (vm.ui.value.members as Loadable.Loaded).value.first { it.name == "Noor" }
    assertFalse(noor.holdsGroupKey)
    vm.hand(noor); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(10), group.handed)
    assertTrue((vm.ui.value.members as Loadable.Loaded).value.first { it.name == "Noor" }.holdsGroupKey)
    assertNull(vm.ui.value.busyMemberId)
  }

  @Test
  fun `a letter is shared with a partner group by name, and only partners the repository offers are listed`() = runTest {
    val group = FakeGroup().apply { partners = listOf(me.paxana.abcmailbox.domain.Group(2, "Northside ABC", null, null, null, null, null, emptyMap(), emptyList(), null, "relay", "active", emptyList(), emptyList(), null)) }
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("Northside ABC"), vm.ui.value.partners.map { it.name })
    vm.share(vm.ui.value.partners.single()); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(41 to 2), group.shared)
    assertEquals("Northside ABC can now read this letter.", vm.ui.value.notice)
  }

  @Test
  fun `a letter moves forward one step at a time and stops at mailed`() = runTest {
    val group = FakeGroup()
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle() // already mailed: nothing happens
    assertEquals(listOf(LetterStatus.PRINTED, LetterStatus.MAILED), group.moves)
    val after = (vm.ui.value.item as Loadable.Loaded).value.letter
    assertEquals(LetterStatus.MAILED, after.status)
    assertEquals("the footer came with the full read and outlives the status answers, which do not carry it", group.footer, after.footer); assertEquals("5476-3594-6", after.replyReference)
    assertEquals("Marked as mailed.", vm.ui.value.notice)
  }

  @Test
  fun `a letter that came back is recorded with its reason and the envelope's words, from mailed only`() = runTest {
    val group = FakeGroup(status = LetterStatus.PRINTED)
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    vm.markReturned(ReturnReason.REFUSED, "too early"); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("a printed letter has not been anywhere to come back from", group.moves.isEmpty())

    group.status = LetterStatus.MAILED; vm.load(); dispatcher.scheduler.advanceUntilIdle()
    vm.markReturned(ReturnReason.TRANSFERRED, "Stamped NOT HERE"); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(LetterStatus.RETURNED), group.moves)
    assertEquals(ReturnedAs(ReturnReason.TRANSFERRED, "Stamped NOT HERE"), group.returnedAs.single())
    assertEquals("an address-type return nudges the member towards the directory", "Recorded as returned. The writer has been told. If your group knows where they are now, the directory needs correcting.", vm.ui.value.notice)
    assertEquals(LetterStatus.RETURNED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)

    val other = FakeGroup(status = LetterStatus.MAILED)
    val vm2 = LetterWorkViewModel(other, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    vm2.markReturned(ReturnReason.RULE_VIOLATION, ""); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Recorded as returned. The writer has been told.", vm2.ui.value.notice)
  }

  @Test
  fun `a letter held since the screen was opened is not printed by oversight, only on purpose`() = runTest {
    val group = FakeGroup()
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    group.held = HeldReason.PRISONER_FREE // the directory learned it a minute ago; this screen has not
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(group.moves.isEmpty())
    assertEquals("said, and the letter shown again with its reason; nobody is asked a question mid-press", "This letter has been held since you opened it. Read why before printing it.", vm.ui.value.notice)
    assertEquals("the screen looked again", 2, group.looks)

    vm.advance(release = true); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(LetterStatus.PRINTED), group.moves); assertEquals(listOf(true), group.releases)
  }

  private fun queued(id: Int, held: HeldReason? = null) = QueueItem(Letter(id, 1, 1, 3, false, LetterStatus.QUEUED, "x", null, 1, null, false, null, null, emptyList(), emptyList(), heldReason = held), null)
  private fun queueVm(group: FakeGroup) = QueueViewModel(group, FakeSessionRepository().apply { signInAs(SessionUser(9, "member1", "Sam", null, "chapter", 1)) }, TestStrings())

  @Test
  fun `a letter night, thirty letters marked together, except the held ones, which are decided one at a time`() = runTest {
    val group = FakeGroup(); val vm = queueVm(group)
    vm.toggle(queued(47)); assertFalse("ticks mean nothing until selecting has started", vm.selection.value.selecting)
    vm.startSelecting()
    vm.toggle(queued(47)); vm.toggle(queued(48)); vm.toggle(queued(4, held = HeldReason.PRISONER_FREE)); vm.toggle(queued(49)); vm.toggle(queued(49))
    assertEquals("the held letter cannot be swept up, and a second tap un-ticks", setOf(47, 48), vm.selection.value.selected)
    vm.markSelected(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(listOf(47, 48) to LetterStatus.PRINTED), group.batches)
    assertEquals("2 letters marked as printed.", vm.selection.value.notice); assertFalse(vm.selection.value.selecting); assertEquals("the list is told to reload", 1, vm.selection.value.done)
  }

  @Test
  fun `a move somebody else made first is a refresh, not a failure, one letter or thirty`() = runTest {
    val meanwhile = AppError.Conflict("Letter 41 was changed by someone else meanwhile; nothing was moved.", "LetterStatusError")
    val one = FakeGroup(refuse = meanwhile); val vm = LetterWorkViewModel(one, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Somebody else got there first; the list has been refreshed.", vm.ui.value.notice); assertEquals("looked again", 2, one.looks)

    val many = FakeGroup().apply { batchRefusal = meanwhile }; val q = queueVm(many); q.startSelecting(); q.toggle(queued(47))
    q.markSelected(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Somebody else got there first; the list has been refreshed.", q.selection.value.notice); assertFalse(q.selection.value.selecting); assertEquals(1, q.selection.value.done)
  }

  @Test
  fun `all or none, and when it is none the ticks stay so that the rest can still go`() = runTest {
    val group = FakeGroup().apply { batchRefusal = AppError.Conflict("Letter 47: a printed letter cannot move to printed.", "LetterStatusError") }
    val vm = queueVm(group); vm.startSelecting(); vm.toggle(queued(47)); vm.toggle(queued(48))
    vm.markSelected(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Nothing was changed. Letter 47: a printed letter cannot move to printed.", vm.selection.value.notice)
    assertEquals(setOf(47, 48), vm.selection.value.selected); assertFalse(vm.selection.value.busy)
  }

  @Test
  fun `the next step follows the filter, a tick never carries from one to another, and some pages have no step to take together`() = runTest {
    val vm = queueVm(FakeGroup())
    assertEquals(LetterStatus.PRINTED, vm.nextStep)
    vm.startSelecting(); vm.toggle(queued(47))
    vm.setFilter(QueueFilter.ByStatus(LetterStatus.PRINTED))
    assertEquals(LetterStatus.MAILED, vm.nextStep); assertFalse("a letter ticked to be printed must not be marked mailed", vm.selection.value.selecting)
    for (f in listOf(QueueFilter.Held, QueueFilter.ByStatus(LetterStatus.MAILED), QueueFilter.ByStatus(LetterStatus.RETURNED))) {
      vm.setFilter(f); assertEquals(null, vm.nextStep); vm.startSelecting(); assertFalse("$f", vm.selection.value.selecting)
    }
  }

  @Test
  fun `a group types one number, and the page says what the public sees, which for a small group is nothing`() = runTest {
    val group = FakeGroup(); val vm = GroupNumbersViewModel(group, TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    val loaded = (vm.ui.value.numbers as Loadable.Loaded).value!!
    assertEquals(null, loaded.published); assertEquals(1, loaded.total); assertEquals("0", vm.ui.value.typed)
    assertFalse("nothing changed, nothing to save", vm.ui.value.canSave)
    vm.onTyped("4o.5-"); assertEquals("digits only: a whole number, not negative", "45", vm.ui.value.typed)
    vm.onTyped("40"); assertTrue(vm.ui.value.canSave)
    vm.save(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(40), group.savedBefore); assertTrue(vm.ui.value.saved)
    assertEquals("the page shows what the server now publishes", "41", (vm.ui.value.numbers as Loadable.Loaded).value?.published)
    vm.onTyped(""); assertFalse("an empty field is not a zero", vm.ui.value.canSave)
  }

  @Test
  fun `a server that does not count yet is said so, with nothing to edit`() = runTest {
    val vm = GroupNumbersViewModel(FakeGroup().apply { numbers = null }, TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(null, (vm.ui.value.numbers as Loadable.Loaded).value); assertFalse(vm.ui.value.canSave)
  }

  @Test
  fun `a refused move shows the API's sentence and leaves the letter as it was`() = runTest {
    val vm = LetterWorkViewModel(FakeGroup(refuse = AppError.Forbidden("Only the letter's relay group can change its status.")), ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Only the letter's relay group can change its status.", vm.ui.value.notice)
    assertEquals(LetterStatus.QUEUED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)
    assertFalse(vm.ui.value.busy)
  }

  @Test
  fun `a token is shown after generating, replaced on regenerate, and gone after revoke`() = runTest {
    val group = FakeGroup()
    val vm = HandoffViewModel(group, HandoffRoute(47, "Maria T."), TestStrings())
    assertNull(vm.ui.value.token)
    vm.generate(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("TOKEN1", vm.ui.value.token?.token)
    vm.generate(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("TOKEN2", vm.ui.value.token?.token)
    vm.revoke(); dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.ui.value.token); assertTrue(vm.ui.value.revoked); assertEquals(1, group.revoked)
  }

  @Test
  fun `a writer needs a name of three to thirty-two characters`() = runTest {
    val vm = AddWriterViewModel(FakeGroup(), TestStrings())
    vm.onName("Al"); assertFalse(vm.ui.value.canSubmit)
    vm.onName("x".repeat(33)); assertFalse(vm.ui.value.canSubmit)
    vm.onName("  Maria T. "); assertTrue(vm.ui.value.canSubmit)
    vm.submit(thenWrite = true); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Maria T.", vm.ui.value.created?.name); assertTrue(vm.ui.value.thenWrite)
  }

  @Test
  fun `printing escapes markup so a letter cannot inject HTML into the print job`() {
    assertEquals("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more", PrintLetter.escape("<script>alert(1)</script> & more"))
  }

  private fun refusal(field: String, code: String, min: Int? = null, max: Int? = null, message: String) =
    AppError.Validation(listOf(message), problems = listOf(me.paxana.abcmailbox.data.api.FieldProblem(field, code, min, max, message)))

  @Test
  fun `a refused writer puts each problem under its field, and an edit clears it`() = runTest {
    val vm = AddWriterViewModel(FakeGroup(refuse = refusal("name", "length_out_of_range", 3, 32, "Name must be between 3 and 32 characters.")), TestStrings())
    vm.onName("Sam"); vm.submit(thenWrite = false); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(mapOf("name" to "Name must be 3 to 32 characters."), vm.ui.value.fieldErrors)
    assertEquals("Check the fields marked in red.", vm.ui.value.error)
    vm.onNote("x")
    assertEquals(emptyMap<String, String>(), vm.ui.value.fieldErrors)
  }

  @Test
  fun `a refused count of letters mailed before goes under the field, with no second line`() = runTest {
    val vm = GroupNumbersViewModel(FakeGroup(refuse = refusal("lettersSentBefore", "out_of_range", 0, 100000, "x")), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    vm.onTyped("5"); vm.save(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Number must be from 0 to 100000.", vm.ui.value.fieldError)
    assertEquals(null, vm.ui.value.error)
  }

  // API #170: a group declines to mail a letter ----------------------------------------------------------------

  @Test
  fun `a letter not yet mailed is declined with its reason, a held one without releasing it, and a mailed one cannot be`() = runTest {
    val group = FakeGroup().apply { held = HeldReason.PRISONER_FREE }
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.mayDecline)
    val why = me.paxana.abcmailbox.data.repo.DeclinedAs(me.paxana.abcmailbox.domain.DeclineReason.FACILITY_RULE, "handwritten_only", "This facility only takes handwritten letters.")
    vm.decline(why); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(LetterStatus.DECLINED), group.moves); assertEquals(listOf(why), group.declinedAs); assertEquals(listOf(false), group.releases)
    assertEquals("Not sent. The writer has been told.", vm.ui.value.notice)
    assertEquals(LetterStatus.DECLINED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)

    val mailed = FakeGroup(LetterStatus.MAILED); val late = LetterWorkViewModel(mailed, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    late.decline(why); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("a mailed letter is in the post: there is nothing left to decline", mailed.moves.isEmpty())
  }

  @Test
  fun `a superadmin is not offered it, since the API lets only the relay group decline`() = runTest {
    val group = FakeGroup()
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings(), isSuperadmin = true); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.ui.value.mayDecline)
    vm.decline(me.paxana.abcmailbox.data.repo.DeclinedAs(me.paxana.abcmailbox.domain.DeclineReason.CONTENT)); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(group.moves.isEmpty())
  }

  @Test
  fun `ticked letters are declined together, and a rule is offered only when every one of their facilities has it`() = runTest {
    fun rule(tag: String) = me.paxana.abcmailbox.domain.MailRule(tag, "paper_and_ink", tag, null)
    val sel = QueueSelection(selected = setOf(1, 2), rulesOf = mapOf(1 to listOf(rule("handwritten_only"), rule("no_stickers")), 2 to listOf(rule("no_stickers"))))
    assertEquals(listOf("no_stickers"), sel.commonRules.map { it.tag })
    assertTrue(QueueSelection(selected = setOf(1, 2), rulesOf = mapOf(1 to listOf(rule("a")), 2 to listOf(rule("b")))).commonRules.isEmpty())

    val group = FakeGroup(); val vm = queueVm(group); vm.startSelecting(); vm.toggle(queued(47)); vm.toggle(queued(48))
    val why = me.paxana.abcmailbox.data.repo.DeclinedAs(me.paxana.abcmailbox.domain.DeclineReason.CONTENT, note = "Threats.")
    vm.declineSelected(why); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(listOf(47, 48) to LetterStatus.DECLINED), group.batches); assertEquals(listOf(why), group.batchDeclined)
    assertEquals("2 letters not sent. Their writers have been told.", vm.selection.value.notice); assertFalse(vm.selection.value.selecting)
  }

  @Test
  fun `a rule goes with a facility-rule decline only, and the note is trimmed to the API's limit`() {
    val r = me.paxana.abcmailbox.data.repo.DeclinedAs(me.paxana.abcmailbox.domain.DeclineReason.CONTENT, rule = "handwritten_only", note = "  " + "x".repeat(250) + " ")
    assertNull("the server refuses a rule on any other reason", r.cleanRule)
    assertEquals(200, r.cleanNote!!.length)
    assertNull(me.paxana.abcmailbox.data.repo.DeclinedAs(me.paxana.abcmailbox.domain.DeclineReason.OTHER, note = "   ").cleanNote)
  }
}

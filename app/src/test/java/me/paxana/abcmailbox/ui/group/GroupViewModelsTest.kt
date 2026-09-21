package me.paxana.abcmailbox.ui.group

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
    override suspend fun queueItem(messageId: Int) = ApiResult.Success(QueueItem(letter(), null))
    /** What came with each move: how it came back, and whether a hold was knowingly released. */
    val returnedAs = mutableListOf<me.paxana.abcmailbox.data.repo.ReturnedAs?>(); val releases = mutableListOf<Boolean>()
    /** Set to make the letter held, as the server would after someone is freed. */
    var held: me.paxana.abcmailbox.domain.HeldReason? = null
    override suspend fun setStatus(messageId: Int, status: LetterStatus, returned: me.paxana.abcmailbox.data.repo.ReturnedAs?, release: Boolean): ApiResult<Letter> {
      refuse?.let { return ApiResult.Failure(it) }
      if (held != null && status == LetterStatus.PRINTED && !release) return ApiResult.Failure(AppError.Conflict("This letter is held.", "LetterHeldError"))
      moves += status; returnedAs += returned; releases += release; this.status = status; held = null; return ApiResult.Success(letter())
    }
    override suspend fun writers() = ApiResult.Success(emptyList<ManagedWriter>())
    override suspend fun addWriter(name: String, email: String?, note: String?) = ApiResult.Success(ManagedWriter(47, name.trim(), email, note, null))
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
    assertTrue(vm.ui.value.notice!!.contains("Hand it to the other members"))

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
    assertEquals(LetterStatus.MAILED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)
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
    assertEquals("an address-type return nudges the member towards the directory", "Recorded. The writer has been told. If you know where they are now, the directory needs correcting.", vm.ui.value.notice)
    assertEquals(LetterStatus.RETURNED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)

    val other = FakeGroup(status = LetterStatus.MAILED)
    val vm2 = LetterWorkViewModel(other, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings()); dispatcher.scheduler.advanceUntilIdle()
    vm2.markReturned(ReturnReason.RULE_VIOLATION, ""); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Recorded. The writer has been told.", vm2.ui.value.notice)
  }

  @Test
  fun `a letter held since the screen was opened is not printed by oversight, only on purpose`() = runTest {
    val group = FakeGroup()
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    group.held = HeldReason.PRISONER_FREE // the directory learned it a minute ago; this screen has not
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("the server's refusal becomes the question the screen would have asked", vm.ui.value.askRelease)
    assertTrue(group.moves.isEmpty()); assertEquals("not a red line to be dismissed", null, vm.ui.value.notice)

    vm.releaseDeclined(); assertFalse(vm.ui.value.askRelease); assertTrue(group.moves.isEmpty())

    vm.advance(release = true); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(LetterStatus.PRINTED), group.moves); assertEquals(listOf(true), group.releases)
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
}

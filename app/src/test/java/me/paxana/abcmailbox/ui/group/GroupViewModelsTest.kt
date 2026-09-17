package me.paxana.abcmailbox.ui.group

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
    override suspend fun setStatus(messageId: Int, status: LetterStatus): ApiResult<Letter> {
      refuse?.let { return ApiResult.Failure(it) }
      moves += status; this.status = status; return ApiResult.Success(letter())
    }
    override suspend fun writers() = ApiResult.Success(emptyList<ManagedWriter>())
    override suspend fun addWriter(name: String, email: String?, note: String?) = ApiResult.Success(ManagedWriter(47, name.trim(), email, note, null))
    override suspend fun issueToken(writerId: Int) = ApiResult.Success(IssuedToken("TOKEN${++issued}", null))
    override suspend fun revokeToken(writerId: Int): ApiResult<Unit> { revoked++; return ApiResult.Success(Unit) }
  }

  @Test
  fun `a letter moves forward one step at a time and stops at mailed`() = runTest {
    val group = FakeGroup()
    val vm = LetterWorkViewModel(group, ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41))
    dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle() // already mailed: nothing happens
    assertEquals(listOf(LetterStatus.PRINTED, LetterStatus.MAILED), group.moves)
    assertEquals(LetterStatus.MAILED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)
    assertEquals("Marked as mailed.", vm.ui.value.notice)
  }

  @Test
  fun `a refused move shows the API's sentence and leaves the letter as it was`() = runTest {
    val vm = LetterWorkViewModel(FakeGroup(refuse = AppError.Forbidden("Only the letter's relay group can change its status.")), ComposeViewModelTest.FakeLetters(), LetterWorkRoute(41))
    dispatcher.scheduler.advanceUntilIdle()
    vm.advance(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Only the letter's relay group can change its status.", vm.ui.value.notice)
    assertEquals(LetterStatus.QUEUED, (vm.ui.value.item as Loadable.Loaded).value.letter.status)
    assertFalse(vm.ui.value.busy)
  }

  @Test
  fun `a token is shown after generating, replaced on regenerate, and gone after revoke`() = runTest {
    val group = FakeGroup()
    val vm = HandoffViewModel(group, HandoffRoute(47, "Maria T."))
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
    val vm = AddWriterViewModel(FakeGroup())
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

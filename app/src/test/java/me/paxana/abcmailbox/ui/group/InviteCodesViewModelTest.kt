package me.paxana.abcmailbox.ui.group

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.InviteRepository
import me.paxana.abcmailbox.domain.InviteBatch
import me.paxana.abcmailbox.domain.InviteQuota
import me.paxana.abcmailbox.domain.IssuedInvites
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.directory.Loadable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InviteCodesViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private class FakeInvites : InviteRepository {
    var loads = 0
    var issueError: AppError? = null
    val cancels = mutableListOf<String?>()
    val issued = mutableListOf<Triple<Int, String?, Int?>>()
    override suspend fun quota(): ApiResult<InviteQuota> { loads++; return ApiResult.Success(InviteQuota(3, 20, listOf(InviteBatch("b1", "Letter night", null, null, 5, 2, 3, 0, 0)))) }
    override suspend fun issue(count: Int, label: String?, days: Int?): ApiResult<IssuedInvites> {
      issued += Triple(count, label, days)
      issueError?.let { return ApiResult.Failure(it) }
      return ApiResult.Success(IssuedInvites("b2", label, null, List(count) { "7Q4M2XKD9HB$it" }, "Portland ABC", 3 + count, 20))
    }
    override suspend fun cancel(batch: String?): ApiResult<Int> { cancels += batch; return ApiResult.Success(3) }
  }

  @Test
  fun `the form refuses what the server would, and a batch made is held until the person says they have it`() = runTest {
    val invites = FakeInvites()
    val vm = InviteCodesViewModel(invites, TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(3, ((vm.ui.value.quota as Loadable.Loaded).value).outstanding)

    vm.onCount("60"); assertFalse("1 to 50", vm.ui.value.canIssue); assertTrue(vm.ui.value.countWrong)
    vm.onCount("0"); assertFalse(vm.ui.value.canIssue)
    vm.onCount("10"); vm.onDays("abc"); assertEquals("", vm.ui.value.daysText); assertTrue(vm.ui.value.canIssue)
    vm.onLabel("Letter night, 2 October"); vm.onDays("7")
    vm.issue(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(Triple(10, "Letter night, 2 October", 7), invites.issued.single())
    assertEquals("ten codes on the screen, once", 10, vm.ui.value.issued?.codes?.size)
    assertEquals("the form is cleared for the next batch", "", vm.ui.value.label)

    vm.finishedWithCodes(); dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.ui.value.issued); assertEquals("the list was reloaded", 2, invites.loads)
  }

  @Test
  fun `over the quota the server's sentence shows, and cancelling asks first then names the batch or all`() = runTest {
    val invites = FakeInvites().apply { issueError = AppError.Conflict("Conflict", "InviteQuotaError", null) }
    val vm = InviteCodesViewModel(invites, TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    vm.issue(); dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.ui.value.issued); assertTrue(vm.ui.value.error != null)

    vm.askCancel("b1"); assertEquals("b1", vm.ui.value.confirmCancel)
    vm.dismissCancel(); assertNull(vm.ui.value.confirmCancel); assertTrue(invites.cancels.isEmpty())
    vm.askCancel("b1"); vm.cancelConfirmed(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("b1"), invites.cancels); assertEquals(3, vm.ui.value.cancelledNotice)
    vm.askCancel(ALL_BATCHES); vm.cancelConfirmed(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("b1", null), invites.cancels)
  }
}

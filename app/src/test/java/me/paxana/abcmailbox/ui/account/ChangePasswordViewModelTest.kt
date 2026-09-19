package me.paxana.abcmailbox.ui.account

import me.paxana.abcmailbox.text.TestStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangePasswordViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  @Test
  fun `the form guards length, match, and reuse`() {
    val vm = ChangePasswordViewModel(FakeSessionRepository(), TestStrings())
    vm.onCurrent("password1"); vm.onNew("short"); vm.onConfirm("short")
    assertFalse(vm.ui.value.canSubmit)
    vm.onNew("password1"); vm.onConfirm("password1")
    assertFalse("same as current", vm.ui.value.canSubmit)
    vm.onNew("brandnewpass"); vm.onConfirm("brandnewpasz")
    assertFalse(vm.ui.value.canSubmit)
    vm.onConfirm("brandnewpass")
    assertTrue(vm.ui.value.canSubmit)
  }

  @Test
  fun `a wrong current password is reported and nothing changes`() = runTest {
    val repo = FakeSessionRepository()
    val vm = ChangePasswordViewModel(repo, TestStrings())
    vm.onCurrent("not-it"); vm.onNew("brandnewpass"); vm.onConfirm("brandnewpass"); vm.submit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Your current password is incorrect.", vm.ui.value.error)
    assertNull(repo.passwordChangedTo)
    assertFalse(vm.ui.value.done)
  }

  @Test
  fun `success clears the form and reports done`() = runTest {
    val repo = FakeSessionRepository()
    val vm = ChangePasswordViewModel(repo, TestStrings())
    vm.onCurrent("password1"); vm.onNew("brandnewpass"); vm.onConfirm("brandnewpass"); vm.submit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("brandnewpass", repo.passwordChangedTo)
    assertTrue(vm.ui.value.done)
    assertEquals("", vm.ui.value.new)
  }
}

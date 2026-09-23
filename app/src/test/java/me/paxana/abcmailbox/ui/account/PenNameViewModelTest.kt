package me.paxana.abcmailbox.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.PenNameRow
import me.paxana.abcmailbox.domain.PenNames
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.auth.FakePenNames
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.auth.PenNameChecker
import me.paxana.abcmailbox.ui.directory.Loadable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PenNameViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  @Test
  fun `the names load, a new name is checked as typed and saved only when free`() = runTest {
    val repo = FakePenNames().apply { names = PenNames("Jim H", listOf(PenNameRow("Jim H", true, null))); taken += "james hollow" }
    val sessions = FakeSessionRepository().apply { signInAs(SessionUser(7, "carol", "Carol", null, "user", null)) }
    val vm = PenNameViewModel(repo, sessions, TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Jim H", (vm.ui.value.names as Loadable.Loaded).value.current)

    vm.onChange("James Hollow"); dispatcher.scheduler.advanceTimeBy(PenNameChecker.DEBOUNCE_MS + 1); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("James Hollow"), repo.checks)
    assertEquals("That pen name is taken.", vm.ui.value.typed.message(TestStrings())); assertFalse(vm.ui.value.canSave)

    vm.onChange("Hollow"); dispatcher.scheduler.advanceTimeBy(PenNameChecker.DEBOUNCE_MS + 1); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Hollow is free. A first and a last part, like a real name, reads better in a mail room.", vm.ui.value.typed.message(TestStrings()))
    assertTrue("a nudge, not a bar", vm.ui.value.canSave)
    vm.save(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Hollow", repo.setTo); assertEquals("Hollow", vm.ui.value.saved)
  }

  @Test
  fun `a name of the wrong shape is refused before any check, and an unreachable check does not block`() = runTest {
    val repo = FakePenNames()
    val vm = PenNameViewModel(repo, FakeSessionRepository(), TestStrings())
    vm.onChange("7 Up"); dispatcher.scheduler.advanceTimeBy(PenNameChecker.DEBOUNCE_MS + 1); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("A pen name starts with a letter.", vm.ui.value.typed.problem); assertTrue(repo.checks.isEmpty()); assertFalse(vm.ui.value.canSave)
    repo.checkError = me.paxana.abcmailbox.data.api.AppError.Network(java.io.IOException())
    vm.onChange("Anna Hollow"); dispatcher.scheduler.advanceTimeBy(PenNameChecker.DEBOUNCE_MS + 1); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.typed.checkFailed); assertFalse("the form may go on; the server checks on save", vm.ui.value.typed.blocks)
  }
}

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
import me.paxana.abcmailbox.ui.common.longDate
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
    assertTrue(vm.ui.value.typed.checkFailed); assertFalse("the form may go on; the server checks on save", vm.ui.value.typed.blocks); assertTrue(vm.ui.value.canSave)
    vm.onChange("Anna Hollow B"); dispatcher.scheduler.runCurrent(); assertFalse("not while the check is still coming", vm.ui.value.canSave)
  }

  private fun idle() = dispatcher.scheduler.advanceUntilIdle()
  private fun type(vm: PenNameViewModel, name: String) { vm.onChange(name); dispatcher.scheduler.advanceTimeBy(PenNameChecker.DEBOUNCE_MS + 1); idle() }
  private val future = java.time.Instant.now().plusSeconds(86_400 * 30)
  private val past = java.time.Instant.now().minusSeconds(86_400)
  private val jim = listOf(PenNameRow("Jim H", true, null), PenNameRow("Sam Hollow", false, null))

  @Test
  fun `during the cooldown there is nothing to type into, and the screen can say when`() = runTest {
    val repo = FakePenNames().apply { names = PenNames("Jim H", jim, changeAllowedAt = future) }
    val vm = PenNameViewModel(repo, FakeSessionRepository(), TestStrings()); idle()
    assertFalse(vm.ui.value.canChange)
    type(vm, "Anna Hollow"); assertFalse(vm.ui.value.canSave)
  }

  @Test
  fun `with no new names left only an own old name can be saved, and the current name never`() = runTest {
    val repo = FakePenNames().apply { names = PenNames("Jim H", jim, changeAllowedAt = past, newNamesLeft = 0) }
    val vm = PenNameViewModel(repo, FakeSessionRepository(), TestStrings()); idle()
    assertTrue(vm.ui.value.canChange)
    type(vm, "Anna Hollow"); assertTrue(vm.ui.value.needsOldName); assertFalse(vm.ui.value.canSave)
    type(vm, "sam  hollow"); assertFalse("an old name of this account's, whatever the case or spacing", vm.ui.value.needsOldName); assertTrue(vm.ui.value.canSave)
    type(vm, "JIM H"); assertFalse("the current name is no change", vm.ui.value.canSave)
  }

  @Test
  fun `a refusal from the server reads the limits again and says the date, not the server's sentence`() = runTest {
    val repo = FakePenNames().apply { names = PenNames("Jim H", jim, changeAllowedAt = past) }
    val vm = PenNameViewModel(repo, FakeSessionRepository(), TestStrings()); idle()
    type(vm, "Anna Hollow")
    // Another device changed the name meanwhile: the server now says wait.
    val allowed = java.time.Instant.parse("2026-12-19T10:00:00Z")
    repo.names = PenNames("Anna Hollow", jim, changeAllowedAt = allowed)
    repo.setError = me.paxana.abcmailbox.data.api.AppError.Conflict("A pen name may be changed once every 90 days.", name = "PenNameLimitError", condition = "cooldown")
    vm.save(); idle()
    assertEquals("Your pen name changed recently, so it can change again on ${allowed.longDate()}.", vm.ui.value.error)
    assertFalse("the limits were read again", vm.ui.value.canChange)
  }

  @Test
  fun `a save that cannot reach the server says the name has not changed`() = runTest {
    val repo = FakePenNames().apply { setError = me.paxana.abcmailbox.data.api.AppError.Network(java.io.IOException()) }
    val vm = PenNameViewModel(repo, FakeSessionRepository(), TestStrings()); idle()
    type(vm, "Anna Hollow"); vm.save(); idle()
    assertEquals("Can't reach the server. Your pen name has not changed.", vm.ui.value.error)
  }
}

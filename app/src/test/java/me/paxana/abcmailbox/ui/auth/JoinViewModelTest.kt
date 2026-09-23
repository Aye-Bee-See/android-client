package me.paxana.abcmailbox.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.nav.JoinRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  @Test
  fun `a malformed code is refused locally and costs no request`() = runTest {
    val repo = FakeSessionRepository()
    val vm = JoinViewModel(repo, JoinRoute(), TestStrings())
    vm.onCodeChange("7q4m-2xkd"); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That is 8 characters; a code has 12.", vm.ui.value.error)
    assertTrue(repo.joinChecks.isEmpty())
  }

  @Test
  fun `check then join makes the account with the folded code, the name and the email, and signs in`() = runTest {
    val repo = FakeSessionRepository()
    val vm = JoinViewModel(repo, JoinRoute(), TestStrings())
    vm.onCodeChange("7q4m 2xkd 9hbO"); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("the O read as a zero, as the server reads it", listOf("7Q4M2XKD9HB0"), repo.joinChecks)
    assertEquals("Portland ABC", vm.ui.value.invitation?.groupName)
    assertEquals("7Q4M-2XKD-9HB0", vm.ui.value.code)

    vm.onUsernameChange("sam"); vm.onPasswordChange("longenough1"); vm.onConfirmChange("longenough1"); vm.onNameChange("Sam")
    assertTrue("nothing to tick: there is no account being taken over", vm.ui.value.canJoin)
    vm.join(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("7Q4M2XKD9HB0", "sam", "longenough1", "", "Sam"), repo.joins.single())
    assertTrue(repo.state.value is SessionState.SignedIn)
    assertEquals("the password does not stay in the state", "", vm.ui.value.password)
  }

  @Test
  fun `arrived by the slip's link, the code is checked at once`() = runTest {
    val repo = FakeSessionRepository()
    val vm = JoinViewModel(repo, JoinRoute("7Q4M-2XKD-9HBT"), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("7Q4M2XKD9HBT"), repo.joinChecks)
    assertNotNull(vm.ui.value.invitation)
  }

  @Test
  fun `a code that is spent is worded on its condition and ends the attempt, and a taken username keeps the code`() = runTest {
    val used = JoinViewModel(FakeSessionRepository().apply { joinInfoError = AppError.Gone("This invite code was already used.", "used") }, JoinRoute(), TestStrings())
    used.onCodeChange("7Q4M-2XKD-9HBT"); used.check(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(used.ui.value.codeDead)
    assertEquals("This invite code has already been used. Ask the group for another.", used.ui.value.error)

    val unknown = JoinViewModel(FakeSessionRepository().apply { joinInfoError = AppError.NotFound("not found") }, JoinRoute(), TestStrings())
    unknown.onCodeChange("7Q4M-2XKD-9HBT"); unknown.check(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(unknown.ui.value.codeDead)
    assertEquals("That is not a code the server knows. Check it against the slip.", unknown.ui.value.error)

    val inactive = JoinViewModel(FakeSessionRepository().apply { joinInfoError = AppError.Gone("The chapter that issued this code is not active.", "inactive") }, JoinRoute(), TestStrings())
    inactive.onCodeChange("7Q4M-2XKD-9HBT"); inactive.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("The group that issued this code is not active on the site any more.", inactive.ui.value.error)

    // A 400 does not spend the code (API PR #116): the screen says so, and the form stays for fixing.
    val taken = FakeSessionRepository(nextError = AppError.Validation(listOf("Username is taken.")))
    val vm = JoinViewModel(taken, JoinRoute("7Q4M-2XKD-9HBT"), TestStrings())
    dispatcher.scheduler.advanceUntilIdle()
    vm.onUsernameChange("sam"); vm.onPasswordChange("longenough1"); vm.onConfirmChange("longenough1"); vm.join(); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(vm.ui.value.codeDead)
    assertEquals("Username is taken. The code was not used up. Fix what is wrong and try again.", vm.ui.value.error)
    assertNotNull("still on the form", vm.ui.value.invitation)
  }
}

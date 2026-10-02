package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.text.TestStrings
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.AppError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setMain() = Dispatchers.setMain(dispatcher)

  @After
  fun resetMainDispatcher() = Dispatchers.resetMain()

  @Test
  fun `cannot submit until both fields are filled`() {
    val vm = LoginViewModel(FakeSessionRepository(), TestStrings())
    assertFalse(vm.uiState.value.canSubmit)
    vm.onUsernameChange("user1")
    assertFalse(vm.uiState.value.canSubmit)
    vm.onPasswordChange("password1")
    assertTrue(vm.uiState.value.canSubmit)
  }

  @Test
  fun `wrong password shows the friendly message and keeps the username`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.Unauthorized("Login failed."))
    val vm = LoginViewModel(repo, TestStrings())
    vm.onUsernameChange("user1")
    vm.onPasswordChange("nope")
    vm.uiState.test {
      assertEquals(null, awaitItem().error)
      vm.onSubmit()
      assertTrue(awaitItem().submitting)
      val done = awaitItem()
      assertFalse(done.submitting)
      assertEquals("Incorrect username or password.", done.error)
      assertEquals("user1", done.username)
    }
  }

  @Test
  fun `network failure shows the connection message`() = runTest {
    val vm = LoginViewModel(FakeSessionRepository(nextError = AppError.Network(IOException())), TestStrings())
    vm.onUsernameChange("user1"); vm.onPasswordChange("password1"); vm.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Can't reach the server. Check your connection and try again.", vm.uiState.value.error)
  }

  @Test
  fun `success clears the password and any error`() = runTest {
    val repo = FakeSessionRepository()
    val vm = LoginViewModel(repo, TestStrings())
    vm.onUsernameChange("user1"); vm.onPasswordChange("password1"); vm.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("", vm.uiState.value.password)
    assertEquals(null, vm.uiState.value.error)
    assertEquals(listOf("user1" to "password1"), repo.attempts)
  }

  @Test
  fun `a right password with two-factor sign-in on shows the code step, a wrong code stays there, and a code signs in`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.TwoFactorNeeded(null))
    val vm = LoginViewModel(repo, TestStrings())
    vm.onUsernameChange("carol"); vm.onPasswordChange("carolpass"); vm.onSubmit(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.uiState.value.needsCode); assertNull("not an error", vm.uiState.value.error); assertEquals("the password is not kept", "", vm.uiState.value.password)

    val code = TwoFactorCodeViewModel(repo, TestStrings())
    code.onCode("12a3 4567"); assertEquals("digits only, six of them", "123456", code.ui.value.code)
    repo.twoFactorError = AppError.Validation(listOf("That code is not right."))
    code.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That code is not right. Check the app shows this account, and type the code it shows now.", code.ui.value.error); assertFalse(code.ui.value.expired)

    repo.twoFactorError = null
    code.toggleRecovery(); code.onCode("abcde-12345"); code.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("123456" to false, "abcde-12345" to true), repo.twoFactorCodes)
    assertTrue(repo.state.value is me.paxana.abcmailbox.data.session.SessionState.SignedIn)
  }

  @Test
  fun `an expired sign-in goes back to the password with the reason`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.TwoFactorNeeded(null)).apply { twoFactorError = AppError.Unauthorized(null) }
    val vm = LoginViewModel(repo, TestStrings()); val code = TwoFactorCodeViewModel(repo, TestStrings())
    vm.onUsernameChange("carol"); vm.onPasswordChange("carolpass"); vm.onSubmit(); dispatcher.scheduler.advanceUntilIdle()
    code.onCode("123456"); code.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(code.ui.value.expired)
    vm.codeExpired(code.ui.value.error.orEmpty())
    assertFalse(vm.uiState.value.needsCode); assertEquals("That took too long. Enter your password again.", vm.uiState.value.error)
  }

  @Test
  fun `the code step starts clean for the next challenge, so one expired sign-in does not send every later one back to the password`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.TwoFactorNeeded(null)).apply { twoFactorError = AppError.Unauthorized(null) }
    val code = TwoFactorCodeViewModel(repo, TestStrings()) // one for the whole sign-in screen, as Hilt scopes it
    code.onCode("123456"); code.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(code.ui.value.expired)
    code.expiredSeen() // what the step does as it hands the reason to the password form
    assertEquals(TwoFactorCodeUiState(), code.ui.value)

    // The password again, a new challenge, and this time a code in time.
    repo.twoFactorError = null
    code.onCode("654321"); code.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(code.ui.value.expired); assertTrue(repo.state.value is me.paxana.abcmailbox.data.session.SessionState.SignedIn)
  }

  @Test
  fun `going back to the password, or leaving the screen any other way, forgets the sign-in that was waiting and what was typed for it`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.TwoFactorNeeded(null))
    val code = TwoFactorCodeViewModel(repo, TestStrings())
    code.toggleRecovery(); code.onCode("abcde-123")
    code.cancel()
    assertEquals(1, repo.twoFactorCancelled); assertEquals(TwoFactorCodeUiState(), code.ui.value)
    // System Back, or the arrow in the top bar: the ViewModel is cleared with the screen (`onCleared` is protected).
    TwoFactorCodeViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(code)
    assertEquals(2, repo.twoFactorCancelled)
  }
}

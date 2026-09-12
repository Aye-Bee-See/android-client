package me.paxana.abcmailbox.ui.auth

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
    val vm = LoginViewModel(FakeSessionRepository())
    assertFalse(vm.uiState.value.canSubmit)
    vm.onUsernameChange("user1")
    assertFalse(vm.uiState.value.canSubmit)
    vm.onPasswordChange("password1")
    assertTrue(vm.uiState.value.canSubmit)
  }

  @Test
  fun `wrong password shows the friendly message and keeps the username`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.Unauthorized("Login failed."))
    val vm = LoginViewModel(repo)
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
    val vm = LoginViewModel(FakeSessionRepository(nextError = AppError.Network(IOException())))
    vm.onUsernameChange("user1"); vm.onPasswordChange("password1"); vm.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Can't reach the server. Check your connection and try again.", vm.uiState.value.error)
  }

  @Test
  fun `success clears the password and any error`() = runTest {
    val repo = FakeSessionRepository()
    val vm = LoginViewModel(repo)
    vm.onUsernameChange("user1"); vm.onPasswordChange("password1"); vm.onSubmit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("", vm.uiState.value.password)
    assertEquals(null, vm.uiState.value.error)
    assertEquals(listOf("user1" to "password1"), repo.attempts)
  }
}

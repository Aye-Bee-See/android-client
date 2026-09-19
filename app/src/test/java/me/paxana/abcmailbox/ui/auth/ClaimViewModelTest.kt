package me.paxana.abcmailbox.ui.auth

import me.paxana.abcmailbox.text.TestStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.nav.ClaimRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val token = "DJ69G5K7XBMYFWW4P4PYTJ8C"

  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  @Test
  fun `a malformed token is refused locally and costs no request`() = runTest {
    val repo = FakeSessionRepository()
    val vm = ClaimViewModel(repo, ClaimRoute(), TestStrings())
    vm.onTokenChange("abc-123"); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That is 6 characters; a token has 24.", vm.ui.value.error)
    assertTrue(repo.claimChecks.isEmpty())
  }

  @Test
  fun `check then claim signs in, sending the normalised token`() = runTest {
    val repo = FakeSessionRepository()
    val vm = ClaimViewModel(repo, ClaimRoute(), TestStrings())
    vm.onTokenChange("dj69-g5k7-xbmy-fww4-p4py-tj8c"); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(token), repo.claimChecks)
    assertEquals("Test Chapter", vm.ui.value.info?.groupName)
    assertEquals("DJ69-G5K7-XBMY-FWW4-P4PY-TJ8C", vm.ui.value.token)

    vm.onUsernameChange("alexwrites"); vm.onPasswordChange("longenough"); vm.onConfirmChange("longenough")
    assertFalse("must tick the box first", vm.ui.value.canClaim)
    vm.onUnderstoodChange(true)
    assertTrue(vm.ui.value.canClaim)
    vm.claim(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(token, "alexwrites", "longenough", ""), repo.claims.single())
    assertTrue(repo.state.value is SessionState.SignedIn)
  }

  @Test
  fun `mismatched passwords and short usernames block the claim`() = runTest {
    val vm = ClaimViewModel(FakeSessionRepository(), ClaimRoute(token), TestStrings())
    dispatcher.scheduler.advanceUntilIdle() // arrived by link: checked automatically
    assertNotNull(vm.ui.value.info)
    vm.onUnderstoodChange(true); vm.onUsernameChange("al"); vm.onPasswordChange("longenough"); vm.onConfirmChange("longenough")
    assertFalse(vm.ui.value.canClaim)
    vm.onUsernameChange("alex"); vm.onConfirmChange("different")
    assertFalse(vm.ui.value.canClaim)
  }

  @Test
  fun `a used or expired token shows the ask-for-a-new-one state`() = runTest {
    val vm = ClaimViewModel(FakeSessionRepository(claimInfoError = AppError.Gone("This claim token has expired.")), ClaimRoute(), TestStrings())
    vm.onTokenChange(token); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.tokenDead)
    assertTrue(vm.ui.value.error!!.contains("Ask the group"))
    assertNull(vm.ui.value.info)
  }

  @Test
  fun `rate limiting surfaces the server's sentence`() = runTest {
    val vm = ClaimViewModel(FakeSessionRepository(claimInfoError = AppError.RateLimited("Too many claim checks. Try again in 42 minute(s).", 2520)), ClaimRoute(), TestStrings())
    vm.onTokenChange(token); vm.check(); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("Too many claim checks. Try again in 42 minute(s).", vm.ui.value.error)
  }
}

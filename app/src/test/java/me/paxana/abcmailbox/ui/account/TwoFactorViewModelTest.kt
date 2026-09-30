package me.paxana.abcmailbox.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.TwoFactorRepository
import me.paxana.abcmailbox.data.repo.TwoFactorSetup
import me.paxana.abcmailbox.data.repo.TwoFactorStatus
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
class TwoFactorViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()
  private fun idle() = dispatcher.scheduler.advanceUntilIdle()

  private class FakeTwoFactor(var status: TwoFactorStatus = TwoFactorStatus(false, null, 0, required = false, requiredBecause = emptyList())) : TwoFactorRepository {
    val confirmed = mutableListOf<String>(); val offWith = mutableListOf<Pair<String, Boolean>>(); var refuse: AppError? = null
    override suspend fun status() = ApiResult.Success(status)
    override suspend fun setup() = ApiResult.Success(TwoFactorSetup("JBSWY3DPEHPK3PXP", "otpauth://totp/letters.support:carol?secret=JBSWY3DPEHPK3PXP&issuer=letters.support"))
    override suspend fun confirm(code: String): ApiResult<List<String>> {
      refuse?.let { return ApiResult.Failure(it) }
      confirmed += code; status = status.copy(enabled = true, recoveryCodesLeft = 10)
      return ApiResult.Success((1..10).map { "AAAAA-0000$it" })
    }
    override suspend fun newRecoveryCodes(code: String) = ApiResult.Success(listOf("BBBBB-00001"))
    override suspend fun switchOff(code: String, recovery: Boolean): ApiResult<Unit> {
      refuse?.let { return ApiResult.Failure(it) }
      offWith += code to recovery; status = status.copy(enabled = false); return ApiResult.Success(Unit)
    }
  }

  @Test
  fun `setting up asks for the app's first code, then shows the recovery codes once and is not done until they are saved`() = runTest {
    val repo = FakeTwoFactor(); val vm = TwoFactorViewModel(repo, TestStrings()); idle()
    vm.startSetup(); idle()
    assertEquals("JBSWY3DPEHPK3PXP", vm.ui.value.setup?.secret)
    vm.onCode("12 34 5"); assertFalse(vm.ui.value.canConfirm)
    repo.refuse = AppError.Validation(listOf("That code is not right."))
    vm.onCode("123456"); vm.confirm(); idle()
    assertEquals("That code is not right. Check the app shows this account, and type the code it shows now.", vm.ui.value.error)
    repo.refuse = null
    vm.onCode("654321"); vm.confirm(); idle()
    assertEquals(listOf("654321"), repo.confirmed)
    assertEquals(10, vm.ui.value.recoveryCodes?.size); assertNull(vm.ui.value.setup)
    vm.doneWithCodes(); assertEquals("not until the person says they saved them", 10, vm.ui.value.recoveryCodes?.size)
    vm.onCodesSaved(true); vm.doneWithCodes()
    assertNull(vm.ui.value.recoveryCodes)
    assertTrue((vm.ui.value.status as Loadable.Loaded).value.enabled)
  }

  @Test
  fun `switching off takes a code or a recovery code, and a requirement is said, not shown as a failure`() = runTest {
    val repo = FakeTwoFactor(TwoFactorStatus(true, null, 10, required = false, requiredBecause = emptyList())); val vm = TwoFactorViewModel(repo, TestStrings()); idle()
    vm.switchOff("abcde-12345", recovery = true); idle()
    assertEquals(listOf("abcde-12345" to true), repo.offWith)
    assertEquals("Two-factor sign-in is off.", vm.ui.value.notice)

    repo.status = TwoFactorStatus(true, null, 10, required = true, requiredBecause = listOf("group")); repo.refuse = AppError.Conflict("It is required.", "TwoFactorError", "required")
    vm.load(); idle(); vm.switchOff("123456", recovery = false); idle()
    assertEquals("It is required for your account, so it cannot be switched off.", vm.ui.value.error)
  }

  @Test
  fun `required and not set up, the screen holds the person until it is`() = runTest {
    val vm = TwoFactorViewModel(FakeTwoFactor(TwoFactorStatus(false, null, 0, required = true, requiredBecause = listOf("group"))), TestStrings()); idle()
    assertTrue(vm.ui.value.mustSetUp)
    vm.startSetup(); idle(); vm.onCode("123456"); vm.confirm(); idle()
    assertFalse("set up now", vm.ui.value.mustSetUp)
  }
}

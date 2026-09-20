package me.paxana.abcmailbox.ui.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.account.AccountEraser
import me.paxana.abcmailbox.data.account.DeletionPreview
import me.paxana.abcmailbox.data.account.DeletionReport
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private class FakeEraser(var next: ApiResult<DeletionReport> = ApiResult.Success(DeletionReport(3, 1, 1, 2, 0))) : AccountEraser {
    val passwords = mutableListOf<String>()
    override val farewell = MutableStateFlow<DeletionReport?>(null)
    override fun farewellSeen() { farewell.value = null }
    override suspend fun preview() = DeletionPreview(threads = 2, unsentOnPhone = 1)
    override suspend fun delete(password: String): ApiResult<DeletionReport> { passwords += password; return next }
  }

  private fun writer() = FakeSessionRepository().apply { signInAs(SessionUser(2, "user1", null, null, "user", null)) }
  private fun vm(eraser: AccountEraser, sessions: FakeSessionRepository = writer(), mode: EncryptionMode = EncryptionMode.SERVER, language: String = "en") =
    DeleteAccountViewModel(eraser, sessions, FixedMode(mode), TestStrings(language))

  @Test
  fun `the button takes both acts, the tick and the password, and pressing it early does nothing`() = runTest {
    val eraser = FakeEraser(); val vm = vm(eraser)
    assertFalse(vm.ui.value.canSubmit)
    vm.onPassword("password1"); assertFalse("a password alone: autofill could have typed it", vm.ui.value.canSubmit)
    vm.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("nothing was sent", eraser.passwords.isEmpty())
    vm.onPassword(""); vm.onAcknowledge(true); assertFalse("a tick alone", vm.ui.value.canSubmit)
    vm.onPassword("password1"); assertTrue(vm.ui.value.canSubmit)
    vm.onAcknowledge(false); assertFalse("and unticking takes it back", vm.ui.value.canSubmit)
  }

  @Test
  fun `the page says how much there is, and what applies to this account`() = runTest {
    val member = FakeSessionRepository().apply { signInAs(SessionUser(5, "member1", null, null, "chapter", 1)) }
    val vm = vm(FakeEraser(), member, EncryptionMode.E2E)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(DeletionPreview(2, 1), vm.ui.value.preview)
    assertTrue(vm.ui.value.endToEnd); assertTrue(vm.ui.value.inGroup)
    val plain = vm(FakeEraser()); dispatcher.scheduler.advanceUntilIdle()
    assertFalse(plain.ui.value.endToEnd); assertFalse(plain.ui.value.inGroup)
  }

  @Test
  fun `a wrong password says nothing was deleted, empties the field, and keeps the tick`() = runTest {
    val eraser = FakeEraser(ApiResult.Failure(AppError.Forbidden("The password is wrong; nothing was deleted.")))
    val vm = vm(eraser); vm.onAcknowledge(true); vm.onPassword("not-it"); vm.submit()
    assertTrue("no second press while the first is out", vm.ui.value.busy)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That password is wrong. Nothing was deleted.", vm.ui.value.error)
    assertEquals("", vm.ui.value.password); assertTrue(vm.ui.value.acknowledged); assertFalse(vm.ui.value.busy)
    assertFalse(vm.ui.value.canSubmit)
  }

  @Test
  fun `a refusal is explained in the person's language, by what kind of account it is`() = runTest {
    val refusal = ApiResult.Failure(AppError.Conflict("This account is the last holder of the key of Test Chapter. Hand the key to another member first (PUT /auth/member-key), or …", "AccountDeleteError"))
    fun messageFor(sessions: FakeSessionRepository, language: String = "en"): String? {
      val vm = vm(FakeEraser(refusal), sessions, language = language); vm.onAcknowledge(true); vm.onPassword("password1"); vm.submit()
      dispatcher.scheduler.advanceUntilIdle(); return vm.ui.value.error
    }
    val member = FakeSessionRepository().apply { signInAs(SessionUser(5, "member1", null, null, "chapter", 1)) }
    val admin = FakeSessionRepository().apply { signInAs(SessionUser(1, "admin", null, null, "admin", null)) }
    assertTrue(messageFor(member)!!.startsWith("You are the last person holding your group’s key."))
    assertFalse("the server's sentence names an endpoint; people never see it", messageFor(member)!!.contains("PUT"))
    assertTrue(messageFor(admin)!!.startsWith("This is the only admin account."))
    assertEquals("The server will not delete this account as things stand. Nothing was deleted.", messageFor(writer()))
    assertTrue(messageFor(member, "ru")!!.startsWith("Вы последний, у кого есть ключ вашей группы."))
    assertTrue(messageFor(member, "es")!!.endsWith("No se ha eliminado nada."))
  }

  @Test
  fun `no connection, and too many guesses, each say so`() = runTest {
    fun messageFor(error: AppError): String? {
      val vm = vm(FakeEraser(ApiResult.Failure(error))); vm.onAcknowledge(true); vm.onPassword("password1"); vm.submit()
      dispatcher.scheduler.advanceUntilIdle()
      assertEquals("a password that may be right stays", "password1", vm.ui.value.password)
      return vm.ui.value.error
    }
    assertEquals("Could not reach the server. Deleting needs a connection; try again when you are online.", messageFor(AppError.Network(java.net.UnknownHostException())))
    assertEquals("Too many attempts. Try again in 15 minutes.", messageFor(AppError.RateLimited("Too many requests", 900)))
  }

  @Test
  fun `success leaves nothing for this screen to do`() = runTest {
    val eraser = FakeEraser(); val vm = vm(eraser); vm.onAcknowledge(true); vm.onPassword("password1"); vm.submit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("password1"), eraser.passwords); assertNull(vm.ui.value.error)
    assertTrue("still 'Deleting…': the shell is about to replace every screen", vm.ui.value.busy)
  }
}

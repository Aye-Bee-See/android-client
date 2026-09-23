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

  private class FakeEraser(var next: ApiResult<DeletionReport> = ApiResult.Success(DeletionReport(3, 1, 1, 2, 0)), var preview: DeletionPreview = DeletionPreview(threads = 2, unsentOnPhone = 1)) : AccountEraser {
    val passwords = mutableListOf<String>(); var previews = 0
    override val farewell = MutableStateFlow<DeletionReport?>(null)
    override fun farewellSeen() { farewell.value = null }
    override suspend fun preview(): DeletionPreview { previews++; return preview }
    override suspend fun delete(password: String): ApiResult<DeletionReport> { passwords += password; return next }
  }

  private fun writer() = FakeSessionRepository().apply { signInAs(SessionUser(2, "user1", null, null, "user", null)) }
  private fun member() = FakeSessionRepository().apply { signInAs(SessionUser(5, "member1", null, null, "chapter", 1)) }
  private fun vm(eraser: AccountEraser, sessions: FakeSessionRepository = writer(), language: String = "en") = DeleteAccountViewModel(eraser, sessions, TestStrings(language))
  /** Everything a person has to do before the last confirmation. */
  private fun DeleteAccountViewModel.fillIn(username: String = "user1", password: String = "password1") { onUsername(username); onPassword(password); onAcknowledge(true) }

  @Test
  fun `the button takes all three, the username, the password and the tick, and pressing early sends nothing`() = runTest {
    val eraser = FakeEraser(); val vm = vm(eraser)
    assertFalse(vm.ui.value.canSubmit)
    vm.onPassword("password1"); vm.onAcknowledge(true); assertFalse("no username", vm.ui.value.canSubmit)
    vm.submit(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue("nothing was sent", eraser.passwords.isEmpty())
    vm.onUsername("user2"); assertFalse("somebody else's", vm.ui.value.canSubmit); assertFalse(vm.ui.value.usernameMatches)
    vm.onUsername("user1"); assertTrue(vm.ui.value.canSubmit)
    vm.onAcknowledge(false); assertFalse("unticking takes it back", vm.ui.value.canSubmit)
    vm.onAcknowledge(true); vm.onPassword(""); assertFalse("a password manager cannot be the one deciding, and neither can its absence", vm.ui.value.canSubmit)
  }

  @Test
  fun `the username proves intent, not identity, so case and stray spaces are forgiven`() {
    val vm = vm(FakeEraser())
    vm.onUsername("  User1 "); assertTrue(vm.ui.value.usernameMatches)
    vm.onUsername("user"); assertFalse("but half of it is not it", vm.ui.value.usernameMatches)
    vm.onUsername(""); assertFalse(vm.ui.value.usernameMatches)
  }

  @Test
  fun `the last holder of a group's key cannot press the button, whatever they typed`() = runTest {
    val eraser = FakeEraser(preview = DeletionPreview(endToEnd = true, isLastKeyHolder = true, membersWhoCouldHoldTheKey = listOf("River")))
    val vm = vm(eraser, member()); dispatcher.scheduler.advanceUntilIdle()
    vm.fillIn(username = "member1")
    assertTrue(vm.ui.value.blockedByGroupKey); assertFalse(vm.ui.value.canSubmit)
    // They go to the Group key screen, hand the key on, and come back: the page asks again.
    eraser.preview = DeletionPreview(endToEnd = true); vm.load(); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(vm.ui.value.canSubmit)
  }

  @Test
  fun `the page knows whose account it is and what applies to it`() = runTest {
    val vm = vm(FakeEraser(), member()); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("member1", vm.ui.value.username); assertTrue(vm.ui.value.inGroup)
    assertEquals(DeletionPreview(threads = 2, unsentOnPhone = 1), vm.ui.value.preview)
    assertFalse(vm(FakeEraser()).ui.value.inGroup)
  }

  @Test
  fun `a wrong password says nothing was deleted, empties the field, and keeps the rest`() = runTest {
    val eraser = FakeEraser(ApiResult.Failure(AppError.Forbidden("The password is wrong; nothing was deleted.")))
    val vm = vm(eraser); vm.fillIn(password = "not-it"); vm.submit()
    assertTrue("no second press while the first is out", vm.ui.value.busy)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals("That is not this account’s password. Nothing was deleted.", vm.ui.value.error)
    assertEquals("", vm.ui.value.password); assertEquals("user1", vm.ui.value.typedUsername); assertTrue(vm.ui.value.acknowledged); assertFalse(vm.ui.value.busy)
    assertFalse(vm.ui.value.canSubmit)
    assertEquals("asked again: a refusal may be about the group key", 2, eraser.previews)
  }

  @Test
  fun `a refusal is explained in the person's language, by what kind of account it is`() = runTest {
    val refusal = ApiResult.Failure(AppError.Conflict("This account is the last holder of the key of Test Chapter. Hand the key to another member first (PUT /auth/member-key), or …", "AccountDeleteError"))
    fun messageFor(sessions: FakeSessionRepository, username: String, language: String = "en"): String? {
      val vm = vm(FakeEraser(refusal), sessions, language); vm.fillIn(username = username); vm.submit()
      dispatcher.scheduler.advanceUntilIdle(); return vm.ui.value.error
    }
    val admin = FakeSessionRepository().apply { signInAs(SessionUser(1, "admin", null, null, "admin", null)) }
    assertTrue(messageFor(member(), "member1")!!.startsWith("You are the last person holding your group’s key."))
    assertFalse("the server's sentence names an endpoint; people never see it", messageFor(member(), "member1")!!.contains("PUT"))
    assertTrue(messageFor(admin, "admin")!!.startsWith("This is the only admin account."))
    val ownerRefusal = ApiResult.Failure(AppError.Conflict("This account is the group-owner admin of Test Chapter, which has other group admins. Make one of them the owner first (PUT /auth/chapter-owner).", "AccountDeleteError"))
    run { val vm = vm(FakeEraser(ownerRefusal), member()); vm.fillIn(username = "member1"); vm.submit(); dispatcher.scheduler.advanceUntilIdle(); assertTrue(vm.ui.value.error!!.startsWith("You are your group’s group-owner admin")) }
    assertEquals("The server will not delete this account as things stand. Nothing was deleted.", messageFor(writer(), "user1"))
    assertTrue(messageFor(member(), "member1", "ru")!!.startsWith("Вы последний, у кого есть ключ вашей группы."))
    assertTrue(messageFor(member(), "member1", "es")!!.endsWith("No se ha eliminado nada."))
  }

  @Test
  fun `no connection, and too many guesses, each say so, and the password never outlives an attempt`() = runTest {
    fun messageFor(error: AppError): String? {
      val vm = vm(FakeEraser(ApiResult.Failure(error))); vm.fillIn(); vm.submit()
      dispatcher.scheduler.advanceUntilIdle()
      assertEquals("", vm.ui.value.password)
      return vm.ui.value.error
    }
    assertEquals("Can’t reach the server. Nothing was deleted.", messageFor(AppError.Network(java.net.UnknownHostException())))
    assertEquals("Too many attempts. Try again in 15 minutes.", messageFor(AppError.RateLimited("Too many requests", 900)))
  }

  @Test
  fun `success leaves nothing for this screen to do`() = runTest {
    val eraser = FakeEraser(); val vm = vm(eraser); vm.fillIn(); vm.submit()
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf("password1"), eraser.passwords); assertNull(vm.ui.value.error)
    assertTrue("still 'Deleting…': the shell is about to replace every screen", vm.ui.value.busy)
  }
}

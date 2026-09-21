package me.paxana.abcmailbox.ui.letters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.MailRules
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.Routing
import me.paxana.abcmailbox.domain.Verification
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.nav.ThreadRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A letter held as `choose_relay` (API PR #106): the writer says who mails it, from the conversation itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private fun group(id: Int, status: String? = "active") = Group(id, "Group $id", "Town", null, null, null, null, emptyMap(), emptyList(), null, "relay", status, emptyList(), emptyList(), null)
  private fun facility(groups: List<Group>) = Facility(10, "Second Prison", emptyList(), null, Routing.RELAY_ONLY, null, null, Verification(null, null), emptyList(), MailRules(), groups)
  private fun prisoner(facilityId: Int? = 10) = Prisoner(3, "Alex", null, emptyList(), facilityId, null, null, null, null, null, null, null, null, emptyList(), null, null, null, null, null, false, Verification(null, null), emptyList())

  private val chosen = mutableListOf<Pair<Int, Int>>()
  private var refuse: AppError? = null
  private val letters = object : LettersRepository by ComposeViewModelTest.FakeLetters() {
    override suspend fun chooseRelay(messageId: Int, groupId: Int): ApiResult<Unit> { refuse?.let { return ApiResult.Failure(it) }; chosen += messageId to groupId; return ApiResult.Success(Unit) }
  }
  private fun vm(prisoner: Prisoner, facility: Facility) = ThreadViewModel(
    letters, FakeSessionRepository().apply { signInAs(SessionUser(2, "user1", null, null, "user", null)) }, ThreadRoute(1), TestStrings(), ComposeViewModelTest.FakeDirectory(prisoner, facility),
  ).also { dispatcher.scheduler.advanceUntilIdle() }

  @Test
  fun `the groups offered are the ones that mail to where the person is now, and the choice is sent`() = runTest {
    val vm = vm(prisoner(), facility(listOf(group(2), group(3), group(4, status = "suspended"))))
    vm.askWhoMails(messageId = 52, prisonerId = 3); dispatcher.scheduler.advanceUntilIdle()
    val question = vm.ui.value.relayQuestion!!
    assertEquals("Second Prison", question.facilityName)
    assertEquals("a suspended group cannot be handed a letter", listOf(2, 3), question.options.map { it.id })

    vm.chooseRelay(question.options[1]); dispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(52 to 3), chosen)
    assertNull(vm.ui.value.relayQuestion); assertEquals("Group 3 will print and mail it.", vm.ui.value.notice)
  }

  @Test
  fun `a facility nobody mails to yet is said so, and no empty question is asked`() = runTest {
    val vm = vm(prisoner(), facility(emptyList()))
    vm.askWhoMails(52, 3); dispatcher.scheduler.advanceUntilIdle()
    assertNull(vm.ui.value.relayQuestion)
    assertEquals("No group is listed yet that mails to Second Prison. The letter waits until one is; you can also delete it.", vm.ui.value.notice)
  }

  @Test
  fun `a directory that does not say where they are, and a server that refuses, are each said plainly`() = runTest {
    val lost = vm(prisoner(facilityId = null), facility(listOf(group(2))))
    lost.askWhoMails(52, 3); dispatcher.scheduler.advanceUntilIdle()
    assertEquals("The directory does not say where they are held now.", lost.ui.value.notice)

    val vm = vm(prisoner(), facility(listOf(group(2))))
    vm.askWhoMails(52, 3); dispatcher.scheduler.advanceUntilIdle()
    refuse = AppError.Validation(listOf("Group 2 does not relay for this facility."))
    vm.chooseRelay(vm.ui.value.relayQuestion!!.options.single()); dispatcher.scheduler.advanceUntilIdle()
    assertTrue(chosen.isEmpty()); assertEquals("Group 2 does not relay for this facility.", vm.ui.value.notice)
  }
}

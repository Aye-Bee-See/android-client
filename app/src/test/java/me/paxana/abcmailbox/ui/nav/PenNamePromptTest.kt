package me.paxana.abcmailbox.ui.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.PenNameRepository
import me.paxana.abcmailbox.data.session.Session
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.PenNameCheck
import me.paxana.abcmailbox.domain.PenNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PenNamePromptTest {
  /** Answers [current] as the account's pen name, or fails when [offline]; counts the reads. */
  private class Names(var current: String? = null, var offline: Boolean = false) : PenNameRepository {
    var reads = 0
    override suspend fun check(name: String): ApiResult<PenNameCheck> = error("not used")
    override suspend fun names(): ApiResult<PenNames> { reads++; return if (offline) ApiResult.Failure(AppError.Network(java.io.IOException("offline"))) else ApiResult.Success(PenNames(current, emptyList())) }
    override suspend fun set(name: String): ApiResult<String> = error("not used")
  }

  private fun signedIn(role: String = "user", penName: String? = null, id: Int = 1) =
    SessionState.SignedIn(Session("t", 0, SessionUser(id, "sam", null, null, role, null, penName)))

  private fun TestScope.prompt(names: Names, state: MutableStateFlow<SessionState>) = PenNamePrompt(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), state, names)

  @Test
  fun `a writer with no pen name is asked once, and again after signing in afresh`() = runTest {
    val state = MutableStateFlow<SessionState>(signedIn())
    val names = Names()
    val p = prompt(names, state)
    assertTrue(p.ask.value)
    p.asked(); advanceUntilIdle()
    assertFalse(p.ask.value)

    state.value = SessionState.SignedOut; advanceUntilIdle()
    state.value = signedIn(); advanceUntilIdle()
    assertTrue(p.ask.value)
    assertEquals(2, names.reads)
  }

  @Test
  fun `the server decides, so an older copy of the session without the name is not asked`() = runTest {
    val p = prompt(Names(current = "Sam Hollow"), MutableStateFlow(signedIn()))
    assertFalse(p.ask.value)
  }

  @Test
  fun `staff, a writer the session knows has a name, and a phone with no connection are not asked`() = runTest {
    val names = Names()
    assertFalse(prompt(names, MutableStateFlow(signedIn(role = "chapter"))).ask.value)
    assertFalse(prompt(names, MutableStateFlow(signedIn(penName = "Sam Hollow"))).ask.value)
    assertEquals("neither was worth a request", 0, names.reads)
    assertFalse(prompt(Names(offline = true), MutableStateFlow(signedIn())).ask.value)
  }
}

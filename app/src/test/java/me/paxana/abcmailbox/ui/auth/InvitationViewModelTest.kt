package me.paxana.abcmailbox.ui.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.GroupInvitation
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.nav.InvitationRoute
import me.paxana.abcmailbox.ui.nav.JoinRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun setMain() = Dispatchers.setMain(dispatcher)
  @After fun resetMainDispatcher() = Dispatchers.resetMain()

  private fun idle() = dispatcher.scheduler.advanceUntilIdle()

  @Test
  fun `a malformed token is refused locally, and an invite code typed here goes to the join screen, both costing no request`() = runTest {
    val repo = FakeSessionRepository()
    val vm = InvitationViewModel(repo, InvitationRoute(), TestStrings())
    vm.onTokenChange("7k2m-9qx4-t8v"); vm.check(); idle()
    assertEquals("That is 11 characters; a token has 24.", vm.ui.value.error)
    vm.onTokenChange("7k2m-9qx4-t8vb-3n6y-1rzc-5wdU"); vm.check(); idle()
    assertEquals("Tokens never contain the character U. Check for a look-alike (I, L, O, and U are not used).", vm.ui.value.error)
    assertNull(vm.ui.value.inviteCode)

    vm.onTokenChange("7q4m-2xkd-9hbt"); vm.check(); idle()
    assertEquals("7Q4M2XKD9HBT", vm.ui.value.inviteCode)
    assertTrue(repo.invitationChecks.isEmpty())
  }

  @Test
  fun `a token no invitation has is offered as a claim token`() = runTest {
    val repo = FakeSessionRepository().apply { invitationError = AppError.NotFound(null) }
    val vm = InvitationViewModel(repo, InvitationRoute(), TestStrings())
    vm.onTokenChange("7k2m 9qx4 t8vb 3n6y 1rzc 5wdh"); vm.check(); idle()
    assertTrue(vm.ui.value.maybeClaimToken); assertFalse(vm.ui.value.tokenDead)
    assertTrue(vm.ui.value.error!!.contains("claim token"))
  }

  @Test
  fun `an expired invitation says to ask for it to be renewed`() = runTest {
    val repo = FakeSessionRepository().apply { invitationError = AppError.Gone("expired", condition = "expired") }
    val vm = InvitationViewModel(repo, InvitationRoute("7K2M9QX4T8VB3N6Y1RZC5WDH"), TestStrings())
    idle()
    assertTrue("checked on arrival", vm.ui.value.tokenDead)
    assertTrue(vm.ui.value.error!!.startsWith("This invitation has expired."))
  }

  @Test
  fun `a member invitation needs an email, then accepts with the folded token and signs in`() = runTest {
    val repo = FakeSessionRepository()
    val vm = InvitationViewModel(repo, InvitationRoute(), TestStrings())
    vm.onTokenChange("7k2m-9qx4-t8vb-3n6y-1rzc-5wdO"); vm.check(); idle()
    assertEquals("the O read as a zero, as the server reads it", listOf("7K2M9QX4T8VB3N6Y1RZC5WD0"), repo.invitationChecks)
    assertEquals("Portland ABC", vm.ui.value.invitation?.groupName)

    vm.onUsernameChange("rae"); vm.onPasswordChange("longenough1"); vm.onConfirmChange("longenough1")
    assertFalse("the API requires an email for a group admin", vm.ui.value.canAccept)
    vm.onEmailChange("rae@example.org")
    assertTrue(vm.ui.value.canAccept)
    vm.accept(); idle()
    val (fields, group) = repo.acceptances.single()
    assertEquals(listOf("7K2M9QX4T8VB3N6Y1RZC5WD0", "rae", "longenough1", "rae@example.org", ""), fields)
    assertNull("a member invitation sends no group", group)
    assertTrue(repo.state.value is SessionState.SignedIn)
    assertEquals("Portland ABC", vm.ui.value.accepted?.groupName)
    assertEquals("the password does not stay in the state", "", vm.ui.value.password)
  }

  @Test
  fun `a group invitation starts with the inviter's name for the group, and needs a city`() = runTest {
    val repo = FakeSessionRepository().apply {
      invitation = GroupInvitation(GroupInvitation.Kind.GROUP, "Riverside ABC", "Portland ABC", null, activatesAtOnce = false, groupFields = setOf("name", "location", "country"))
    }
    val vm = InvitationViewModel(repo, InvitationRoute("7K2M9QX4T8VB3N6Y1RZC5WDH"), TestStrings())
    idle()
    assertEquals("Riverside ABC", vm.ui.value.group.name)
    vm.onUsernameChange("rae"); vm.onPasswordChange("longenough1"); vm.onConfirmChange("longenough1"); vm.onEmailChange("rae@example.org")
    assertFalse(vm.ui.value.canAccept)
    vm.onGroupChange { it.copy(city = "Riverside") }
    assertTrue(vm.ui.value.canAccept)
    vm.accept(); idle()
    assertEquals("Riverside", repo.acceptances.single().second?.city)
    assertEquals(false, vm.ui.value.accepted?.activeNow)
  }

  @Test
  fun `a refused acceptance says the invitation is still good`() = runTest {
    val repo = FakeSessionRepository(nextError = AppError.Validation(listOf("Username taken.")))
    val vm = InvitationViewModel(repo, InvitationRoute("7K2M9QX4T8VB3N6Y1RZC5WDH"), TestStrings())
    idle()
    vm.onUsernameChange("rae"); vm.onPasswordChange("longenough1"); vm.onConfirmChange("longenough1"); vm.onEmailChange("rae@example.org")
    vm.accept(); idle()
    assertEquals("Username taken. Nothing was made and the invitation is still good. Fix what is wrong and try again.", vm.ui.value.error)
    assertFalse(vm.ui.value.tokenDead)
  }

  @Test
  fun `a 24-character token typed on the join screen is handed to the invitation screen unchecked`() = runTest {
    val repo = FakeSessionRepository()
    val vm = JoinViewModel(repo, JoinRoute(), TestStrings(), FakePenNames())
    vm.onCodeChange("7k2m-9qx4-t8vb-3n6y-1rzc-5wdh"); vm.check(); idle()
    assertEquals("7K2M9QX4T8VB3N6Y1RZC5WDH", vm.ui.value.invitationToken)
    assertTrue(repo.joinChecks.isEmpty())
  }
}

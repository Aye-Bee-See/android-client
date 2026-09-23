package me.paxana.abcmailbox.data.account

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.activity.ActivityRepository
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.push.PushProvider
import me.paxana.abcmailbox.data.push.PushRegistrar
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.GroupRepository
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.OutboxItem
import me.paxana.abcmailbox.data.repo.OutboxRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.Activity
import me.paxana.abcmailbox.domain.GroupMember
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.group.GroupViewModelsTest
import me.paxana.abcmailbox.ui.letters.ComposeViewModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountEraserTest {
  private val sessions = FakeSessionRepository().apply { signInAs(SessionUser(2, "user1", null, null, "user", null)) }
  private val told = mutableListOf<String>()

  private val letters = object : LettersRepository by ComposeViewModelTest.FakeLetters() { override suspend fun threadCount(): Int? = 4 }
  private val outbox = object : OutboxRepository by ComposeViewModelTest.FakeOutbox() {
    override fun items(): Flow<List<OutboxItem>> = flowOf(emptyList())
    override suspend fun eraseFor(userId: Int): Int { told += "outbox:$userId"; return 2 }
  }
  private val drafts = object : DraftsRepository by ComposeViewModelTest.FakeDrafts() { override suspend fun eraseFor(userId: Int) { told += "drafts:$userId"; error("a store that fails must not spare the others") } }
  private val files = object : LocalFilesContract {
    override fun newCameraTarget(): Pair<File, Uri> = error("not used")
    override fun stageCameraShot(file: File): StagedFile = error("not used")
    override suspend fun stage(uri: Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) = Unit
    override fun downloadTarget(attachmentId: Int, name: String): File = error("not used")
    override fun emptyCaches() { told += "caches" }
  }
  private val push = object : PushRegistrar {
    override val availability = PushProvider.Availability.READY
    override val enabled = flowOf(true)
    override suspend fun turnOn(): ApiResult<Boolean> = error("not used")
    override suspend fun turnOff(): ApiResult<Unit> = error("the server's record went with the account; asking it would only fail")
    override fun onNewToken(token: String) = Unit
    override suspend fun forgetLocally() { told += "push" }
  }
  private val activity = object : ActivityRepository {
    override val unread = MutableStateFlow(3)
    override val arrivals = MutableSharedFlow<List<Activity>>()
    override suspend fun sync(announce: Boolean): List<Activity> = emptyList()
    override suspend fun markAllRead() = Unit
    override suspend fun forget(userId: Int) { told += "activity:$userId" }
  }
  private var members = listOf<GroupMember>()
  private val group = object : GroupRepository by GroupViewModelsTest.FakeGroup() { override suspend fun members(): ApiResult<List<GroupMember>> = ApiResult.Success(members) }
  private fun eraser(mode: EncryptionMode = EncryptionMode.SERVER) = DefaultAccountEraser(sessions, letters, outbox, drafts, files, push, activity, FixedMode(mode), group)
  private val eraser = eraser()

  @Test
  fun `before anything is asked, a writer is told how many conversations are theirs`() = runTest {
    assertEquals(DeletionPreview(threads = 4), eraser.preview())
  }

  @Test
  fun `a group member is not, because the list they see is the group's, and the group's letters stay`() = runTest {
    sessions.signInAs(SessionUser(5, "member1", null, null, "chapter", 1))
    assertNull(eraser.preview().threads)
  }

  @Test
  fun `the group-owner admin of a group with other admins is told to pass the role on first, in either mode`() = runTest {
    sessions.signInAs(SessionUser(5, "member1", null, null, "chapter", 1))
    members = listOf(GroupMember(5, "Mem One", hasOwnKey = true, holdsGroupKey = true, isMe = true, isOwner = true), GroupMember(6, "River", hasOwnKey = true, holdsGroupKey = true, isMe = false))
    assertTrue(eraser(EncryptionMode.SERVER).preview().isOwnerWithOthers)
    members = members.take(1)
    assertFalse("alone in the group: the role dies with the account, and the server allows it", eraser(EncryptionMode.SERVER).preview().isOwnerWithOthers)
    members = listOf(GroupMember(5, "Mem One", hasOwnKey = true, holdsGroupKey = false, isMe = true), GroupMember(6, "River", hasOwnKey = true, holdsGroupKey = true, isMe = false, isOwner = true))
    assertFalse("not the owner", eraser(EncryptionMode.E2E).preview().isOwnerWithOthers)
  }

  @Test
  fun `the only holder of a group's key is told before they type anything, and who could take it`() = runTest {
    sessions.signInAs(SessionUser(5, "member1", null, null, "chapter", 1))
    members = listOf(GroupMember(5, "Mem One", hasOwnKey = true, holdsGroupKey = true, isMe = true), GroupMember(6, "River", hasOwnKey = true, holdsGroupKey = false, isMe = false), GroupMember(7, "Not yet signed in", hasOwnKey = false, holdsGroupKey = false, isMe = false))
    val alone = eraser(EncryptionMode.E2E).preview()
    assertTrue(alone.endToEnd); assertTrue(alone.isLastKeyHolder)
    assertEquals("only someone with a key of their own can be handed the group's", listOf("River"), alone.membersWhoCouldHoldTheKey)

    members = members.map { if (it.id == 6) it.copy(holdsGroupKey = true) else it }
    assertFalse("River holds it too now: free to go", eraser(EncryptionMode.E2E).preview().isLastKeyHolder)
    assertFalse("and in server mode there is no group key to strand", eraser(EncryptionMode.SERVER).preview().isLastKeyHolder)
  }

  @Test
  fun `a deleted account leaves nothing of its own on the phone, and a receipt`() = runTest {
    val report = (eraser.delete("password1") as ApiResult.Success).value
    assertEquals(listOf("outbox:2", "drafts:2", "caches", "push", "activity:2"), told)
    assertEquals(DeletionReport(letters = 3, replies = 1, attachments = 1, threads = 2, unsentOnPhone = 2), report)
    assertTrue(sessions.state.value is SessionState.SignedOut)
    assertEquals("kept until the person has read it", report, eraser.farewell.value)
    eraser.farewellSeen(); assertNull(eraser.farewell.value)
  }

  @Test
  fun `a wrong password wipes nothing`() = runTest {
    val result = eraser.delete("not-it")
    assertTrue((result as ApiResult.Failure).error is AppError.Forbidden)
    assertTrue(told.isEmpty()); assertNull(eraser.farewell.value)
    assertTrue(sessions.state.value is SessionState.SignedIn)
  }
}

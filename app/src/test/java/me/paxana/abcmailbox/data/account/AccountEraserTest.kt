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
import me.paxana.abcmailbox.data.files.LocalFilesContract
import me.paxana.abcmailbox.data.files.StagedFile
import me.paxana.abcmailbox.data.push.PushProvider
import me.paxana.abcmailbox.data.push.PushRegistrar
import me.paxana.abcmailbox.data.repo.DraftsRepository
import me.paxana.abcmailbox.data.repo.LettersRepository
import me.paxana.abcmailbox.data.repo.OutboxItem
import me.paxana.abcmailbox.data.repo.OutboxRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.domain.Activity
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import me.paxana.abcmailbox.ui.letters.ComposeViewModelTest
import org.junit.Assert.assertEquals
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
  private val eraser = DefaultAccountEraser(sessions, letters, outbox, drafts, files, push, activity)

  @Test
  fun `before anything is asked, the page can say how much there is`() = runTest {
    assertEquals(DeletionPreview(threads = 4, unsentOnPhone = 0), eraser.preview())
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

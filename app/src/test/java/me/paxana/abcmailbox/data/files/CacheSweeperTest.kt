package me.paxana.abcmailbox.data.files

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Readable copies in the cache (opened letters, staged files, printed slips) go whenever nobody is signed in. */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheSweeperTest {
  private var emptied = 0
  private val files = object : LocalFilesContract {
    override fun newCameraTarget(): Pair<File, Uri> = error("not used")
    override fun stageCameraShot(file: File): StagedFile = error("not used")
    override suspend fun stage(uri: Uri): StagedFile = error("not used")
    override fun discard(staged: StagedFile) = Unit
    override fun downloadTarget(attachmentId: Int, name: String): File = error("not used")
    override fun emptyCaches() { emptied++ }
  }

  @Test
  fun `the cache is emptied at a start signed out, and again at each sign-out, never while signed in`() = runTest {
    val sessions = FakeSessionRepository() // starts signed out
    CacheSweeper(sessions, files, TestScope(UnconfinedTestDispatcher())).start()
    assertEquals("signed out from the start: whatever an earlier session left goes", 1, emptied)
    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
    assertEquals("signed in: opened letters stay in the cache while they are read", 1, emptied)
    sessions.logout()
    assertEquals(2, emptied)
  }

  @Test
  fun `a sign-out followed at once by another account's sign-in, seen as one change, is swept all the same`() = runTest {
    val sessions = FakeSessionRepository().apply { signInAs(SessionUser(2, "user1", null, null, "user", null)) }
    val scope = TestScope(StandardTestDispatcher()) // nothing runs until told, as on a busy phone
    CacheSweeper(sessions, files, scope).start()
    scope.testScheduler.runCurrent()
    assertEquals("signed in from the start: nothing to sweep", 0, emptied)
    sessions.logout(); sessions.signInAs(SessionUser(3, "user2", null, null, "user", null))
    scope.testScheduler.runCurrent() // the collector sees only the latest: user 3, never the signed-out moment
    assertEquals("what user 1 could read is gone before user 2 reads anything", 1, emptied)
  }
}

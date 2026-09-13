package me.paxana.abcmailbox.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.db.AppDatabase
import me.paxana.abcmailbox.data.session.KeystoreSecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Room plus the Keystore cipher: a draft survives, is scoped per user, and is not plaintext in the table. */
@RunWith(AndroidJUnit4::class)
class DraftsRepositoryTest {

  @Test
  fun roundTrip() = runTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val repo = DefaultDraftsRepository(db.drafts(), KeystoreSecretCipher())

    assertNull(repo.load(1, 3))
    repo.save(1, 3, Draft("Dear Alex, the tomatoes", "two pages", 2, 5L))
    assertEquals("Dear Alex, the tomatoes", repo.load(1, 3)?.body)
    assertEquals(2, repo.load(1, 3)?.relayChapter)
    assertNull(repo.load(2, 3)) // another account on the same phone
    assertFalse(db.drafts().get(1, 3)!!.body.contains("tomatoes"))

    repo.delete(1, 3)
    assertNull(repo.load(1, 3))
    db.close()
  }
}

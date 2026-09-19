package me.paxana.abcmailbox.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A database made by version 1 of the app (drafts only) must open in version 2 (drafts plus the
 * offline directory) with its drafts intact. The helper builds the old database from the schema
 * file Room exported at the time (`app/schemas/.../1.json`), which is why those files are committed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
  private val name = "migration-test.db"

  @get:Rule
  val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

  @Test
  fun a_draft_written_by_version_1_survives_the_upgrade_to_version_2() = runTest {
    helper.createDatabase(name, 1).apply {
      execSQL("INSERT INTO drafts (userId, prisonerId, body, note, relayChapter, updatedAt) VALUES (2, 1, 'ciphertext-of-a-half-written-letter', NULL, 1, 1758000000000)")
      close()
    }
    // Validates the upgraded schema against what version 2 expects, table by table.
    helper.runMigrationsAndValidate(name, 2, true).close()

    val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, name).build()
    try {
      val draft = db.drafts().get(userId = 2, prisonerId = 1)
      assertNotNull(draft); assertEquals("ciphertext-of-a-half-written-letter", draft!!.body)
      assertEquals("the new tables exist and are empty", 0, db.directoryCache().countPrisoners("", null, null, null, null))
    } finally { db.close() }
  }
}

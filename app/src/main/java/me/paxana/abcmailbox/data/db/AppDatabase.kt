package me.paxana.abcmailbox.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * An unsent letter, saved as the writer types so nothing is lost to a phone
 * call or a crash. Keyed by account and prisoner: one draft per thread, and a
 * different account on the same phone never sees another's draft. `body` and
 * `note` are ciphertext (see [me.paxana.abcmailbox.data.repo.DraftsRepository]).
 */
@Entity(tableName = "drafts", primaryKeys = ["userId", "prisonerId"])
data class DraftEntity(
  val userId: Int,
  val prisonerId: Int,
  val body: String,
  val note: String?,
  val relayChapter: Int?,
  val updatedAt: Long,
)

@Dao
interface DraftDao {
  @Query("SELECT * FROM drafts WHERE userId = :userId AND prisonerId = :prisonerId")
  suspend fun get(userId: Int, prisonerId: Int): DraftEntity?

  @Upsert
  suspend fun upsert(draft: DraftEntity)

  @Query("DELETE FROM drafts WHERE userId = :userId AND prisonerId = :prisonerId")
  suspend fun delete(userId: Int, prisonerId: Int)

  @Query("DELETE FROM drafts")
  suspend fun clear()
}

@Database(entities = [DraftEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
  abstract fun drafts(): DraftDao
}

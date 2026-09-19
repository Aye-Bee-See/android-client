package me.paxana.abcmailbox.data.db

import androidx.room.AutoMigration
import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
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

/**
 * Version 2 adds the offline directory tables. The change only adds tables, so Room writes the
 * upgrade itself (an auto-migration, generated at compile time by comparing the exported schema
 * files in `app/schemas`); drafts already on a phone are untouched. Version 3 adds the outbox the same way.
 */
@Database(
  entities = [DraftEntity::class, CachedPrisoner::class, CachedFacility::class, CachedGroup::class, DirectoryMeta::class, OutboxEntity::class],
  version = 4,
  exportSchema = true,
  autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4, spec = AppDatabase.DropOutcomeUnknown::class)],
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun drafts(): DraftDao
  abstract fun directoryCache(): DirectoryCacheDao
  abstract fun outbox(): OutboxDao

  /**
   * Version 4: the outbox no longer guesses whether a letter arrived (the API has idempotency keys), so the
   * flag that said "go and look" goes. Dropping a column is not something Room can infer is intended, hence the spec.
   */
  @DeleteColumn(tableName = "outbox", columnName = "outcomeUnknown")
  class DropOutcomeUnknown : AutoMigrationSpec
}

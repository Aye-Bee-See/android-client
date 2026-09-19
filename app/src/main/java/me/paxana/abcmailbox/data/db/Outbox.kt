package me.paxana.abcmailbox.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A letter written without a connection, waiting to go. Everything that says who it is to or
 * what it says (`sealed`) is ciphertext under the Keystore key, like a draft: a queued letter is
 * a plaintext letter sitting on a phone for hours, which is what drafts already guard against.
 * What stays readable is only what the sender needs to find and order the rows.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** Whose letter this is. Only that account's session ever sends it. */
  val userId: Int,
  /** Ciphertext of an [me.paxana.abcmailbox.data.repo.OutboxPayload] as JSON. */
  val sealed: String,
  val queuedAt: Long,
  /** `waiting`, or `refused` once the server has said no (or yes to the letter and no to a file). */
  val state: String = STATE_WAITING,
  val attempts: Int = 0,
  /** Set as soon as the server has the letter, so a retry carries on with its files and never posts it twice. */
  val messageId: Int? = null,
) {
  companion object { const val STATE_WAITING = "waiting"; const val STATE_REFUSED = "refused" }
}

@Dao
interface OutboxDao {
  @Query("SELECT * FROM outbox WHERE userId = :userId ORDER BY queuedAt ASC") fun observe(userId: Int): Flow<List<OutboxEntity>>
  @Query("SELECT * FROM outbox WHERE userId = :userId AND state = 'waiting' ORDER BY queuedAt ASC") suspend fun waiting(userId: Int): List<OutboxEntity>
  @Query("SELECT COUNT(*) FROM outbox WHERE state = 'waiting'") suspend fun countWaiting(): Int
  @Query("SELECT * FROM outbox WHERE id = :id") suspend fun get(id: Long): OutboxEntity?
  @Insert suspend fun insert(row: OutboxEntity): Long
  @Update suspend fun update(row: OutboxEntity)
  @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
}

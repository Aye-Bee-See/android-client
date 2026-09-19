package me.paxana.abcmailbox.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * The offline copy of the public directory. Each table keeps a record as the JSON the API
 * sent (`json`), plus only the columns needed to search, filter and sort it the way the
 * server does. Keeping the JSON whole means the API can add fields without a migration
 * here, and the offline path reuses the DTOs and mappers of the online one.
 */

@Entity(tableName = "dir_prisoners")
data class CachedPrisoner(
  @PrimaryKey val id: Int,
  /** Chosen name, else birth name: what the server sorts `name` by. */
  val sortName: String,
  /** Lower-cased birth and chosen names, which is what `q` matches on the server. */
  val searchText: String,
  val status: String?,
  val country: String?,
  val featured: Boolean,
  val facilityId: Int?,
  val createdAt: String?,
  val json: String,
)

@Entity(tableName = "dir_facilities")
data class CachedFacility(
  @PrimaryKey val id: Int,
  val sortName: String,
  val searchText: String,
  val country: String?,
  val routing: String?,
  /** At least one active relay group, as the server's `relay=true` means. */
  val hasRelay: Boolean,
  val createdAt: String?,
  val json: String,
)

@Entity(tableName = "dir_groups")
data class CachedGroup(
  @PrimaryKey val id: Int,
  val sortName: String,
  val searchText: String,
  val country: String?,
  /** Service keys wrapped in bars, `|a|b|`, so one LIKE finds a whole key and never half of one. */
  val services: String,
  val networkRole: String?,
  val createdAt: String?,
  val json: String,
)

/** Small facts about the copy: when it was made, and the rule list that goes with it. */
@Entity(tableName = "dir_meta")
data class DirectoryMeta(@PrimaryKey val key: String, val value: String)

/** How many of each the copy holds; shown so a volunteer can see it is complete before a letter night. */
data class DirectoryCounts(val prisoners: Int, val facilities: Int, val groups: Int)

// One ORDER BY serves every sort the API offers; an unknown sort falls through to id, the API's default.
private const val ORDER = """
  ORDER BY CASE WHEN :sort = 'name' THEN sortName END COLLATE NOCASE ASC,
           CASE WHEN :sort = 'newest' THEN createdAt END DESC,
           CASE WHEN :sort = 'oldest' THEN createdAt END ASC,
           id ASC
  LIMIT :limit OFFSET :offset"""

private const val PRISONER_WHERE = """
  WHERE (:q = '' OR searchText LIKE '%' || :q || '%')
    AND (:status IS NULL OR status = :status)
    AND (:country IS NULL OR country = :country)
    AND (:featured IS NULL OR featured = :featured)
    AND (:facilityId IS NULL OR facilityId = :facilityId)"""

private const val FACILITY_WHERE = """
  WHERE (:q = '' OR searchText LIKE '%' || :q || '%')
    AND (:country IS NULL OR country = :country)
    AND (:routing IS NULL OR routing = :routing)
    AND (:relay IS NULL OR hasRelay = :relay)"""

// A group marked `both` answers to either role, as on the server.
private const val GROUP_WHERE = """
  WHERE (:q = '' OR searchText LIKE '%' || :q || '%')
    AND (:country IS NULL OR country = :country)
    AND (:service IS NULL OR services LIKE '%|' || :service || '|%')
    AND (:role IS NULL OR networkRole = :role OR networkRole = 'both')"""

@Dao
abstract class DirectoryCacheDao {
  @Query("SELECT json FROM dir_prisoners $PRISONER_WHERE $ORDER")
  abstract suspend fun prisoners(q: String, status: String?, country: String?, featured: Boolean?, facilityId: Int?, sort: String, limit: Int, offset: Int): List<String>
  @Query("SELECT COUNT(*) FROM dir_prisoners $PRISONER_WHERE")
  abstract suspend fun countPrisoners(q: String, status: String?, country: String?, featured: Boolean?, facilityId: Int?): Int

  @Query("SELECT json FROM dir_facilities $FACILITY_WHERE $ORDER")
  abstract suspend fun facilities(q: String, country: String?, routing: String?, relay: Boolean?, sort: String, limit: Int, offset: Int): List<String>
  @Query("SELECT COUNT(*) FROM dir_facilities $FACILITY_WHERE")
  abstract suspend fun countFacilities(q: String, country: String?, routing: String?, relay: Boolean?): Int

  @Query("SELECT json FROM dir_groups $GROUP_WHERE $ORDER")
  abstract suspend fun groups(q: String, country: String?, service: String?, role: String?, sort: String, limit: Int, offset: Int): List<String>
  @Query("SELECT COUNT(*) FROM dir_groups $GROUP_WHERE")
  abstract suspend fun countGroups(q: String, country: String?, service: String?, role: String?): Int

  @Query("SELECT json FROM dir_prisoners WHERE id = :id") abstract suspend fun prisoner(id: Int): String?
  @Query("SELECT json FROM dir_facilities WHERE id = :id") abstract suspend fun facility(id: Int): String?
  @Query("SELECT json FROM dir_groups WHERE id = :id") abstract suspend fun group(id: Int): String?

  @Query("SELECT value FROM dir_meta WHERE `key` = :key") abstract suspend fun meta(key: String): String?
  @Query("SELECT value FROM dir_meta WHERE `key` = :key") abstract fun observeMeta(key: String): Flow<String?>
  @Query("SELECT (SELECT COUNT(*) FROM dir_prisoners) AS prisoners, (SELECT COUNT(*) FROM dir_facilities) AS facilities, (SELECT COUNT(*) FROM dir_groups) AS `groups`")
  abstract fun observeCounts(): Flow<DirectoryCounts>

  @Insert abstract suspend fun insertPrisoners(rows: List<CachedPrisoner>)
  @Insert abstract suspend fun insertFacilities(rows: List<CachedFacility>)
  @Insert abstract suspend fun insertGroups(rows: List<CachedGroup>)
  @Upsert abstract suspend fun putMeta(meta: DirectoryMeta)
  @Query("DELETE FROM dir_prisoners") abstract suspend fun clearPrisoners()
  @Query("DELETE FROM dir_facilities") abstract suspend fun clearFacilities()
  @Query("DELETE FROM dir_groups") abstract suspend fun clearGroups()

  /**
   * Swaps the whole copy in one transaction: a reader sees the old directory or the new one,
   * never half of each, and a download that fails part-way has written nothing.
   */
  @Transaction
  open suspend fun replaceAll(prisoners: List<CachedPrisoner>, facilities: List<CachedFacility>, groups: List<CachedGroup>, meta: List<DirectoryMeta>) {
    clearPrisoners(); clearFacilities(); clearGroups()
    insertPrisoners(prisoners); insertFacilities(facilities); insertGroups(groups)
    meta.forEach { putMeta(it) }
  }
}

package me.paxana.abcmailbox.data.activity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A group that will not mail this writer's letters (API #171): its name and the reason it gave, which the writer is told. */
@Serializable
data class GroupBlockNotice(val groupName: String? = null, val reason: String? = null)

/**
 * What the writer was told when a group blocked them. The feed is the only place the API says it (`writer.block`), and
 * a feed sentence names no group and quotes nothing, since it can land on a lock screen. So the group and its reason
 * are kept here, per account, for the app to say where only the writer sees them: beside a held letter, and when a
 * letter is refused. A lifted block is forgotten. A block announced to another of the writer's devices first is not
 * here; the letter is still held, and said to be, without the reason.
 */
interface GroupBlockNotices {
  /** Keyed by the group's id. */
  fun notices(userId: Int): Flow<Map<Int, GroupBlockNotice>>
  suspend fun blocked(userId: Int, groupId: Int, notice: GroupBlockNotice)
  suspend fun lifted(userId: Int, groupId: Int)
  suspend fun forget(userId: Int) {}

  object None : GroupBlockNotices {
    override fun notices(userId: Int): Flow<Map<Int, GroupBlockNotice>> = flowOf(emptyMap())
    override suspend fun blocked(userId: Int, groupId: Int, notice: GroupBlockNotice) {}
    override suspend fun lifted(userId: Int, groupId: Int) {}
  }
}

@Singleton
class DataStoreGroupBlockNotices @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
  private val cipher: me.paxana.abcmailbox.data.session.SecretCipher,
) : GroupBlockNotices {
  private val serializer = MapSerializer(Int.serializer(), GroupBlockNotice.serializer())
  private fun key(userId: Int) = stringPreferencesKey("group_block_notices_$userId")
  // Sealed under the Keystore key like the session and the drafts: a group's name and its words about this writer are
  // nobody else's to read off the phone. A value written in the clear by the release before this one is still read.
  private fun seal(value: String) = java.util.Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
  private fun open(stored: String): String? = runCatching { String(cipher.decrypt(java.util.Base64.getDecoder().decode(stored)), Charsets.UTF_8) }.getOrNull()
  private fun read(prefs: Preferences, userId: Int): Map<Int, GroupBlockNotice> =
    prefs[key(userId)]?.let { stored -> (open(stored) ?: stored).let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } }.orEmpty()

  override fun notices(userId: Int): Flow<Map<Int, GroupBlockNotice>> = dataStore.data.map { read(it, userId) }

  override suspend fun blocked(userId: Int, groupId: Int, notice: GroupBlockNotice) {
    dataStore.edit { it[key(userId)] = seal(json.encodeToString(serializer, read(it, userId) + (groupId to notice))) }
  }

  override suspend fun lifted(userId: Int, groupId: Int) {
    dataStore.edit { prefs -> (read(prefs, userId) - groupId).let { if (it.isEmpty()) prefs.remove(key(userId)) else prefs[key(userId)] = seal(json.encodeToString(serializer, it)) } }
  }

  override suspend fun forget(userId: Int) { dataStore.edit { it.remove(key(userId)) } }
}

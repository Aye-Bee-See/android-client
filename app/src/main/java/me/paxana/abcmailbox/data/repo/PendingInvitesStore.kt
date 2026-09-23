package me.paxana.abcmailbox.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.domain.IssuedInvites
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A batch of invite codes just issued and not yet printed or saved (API PR #116). The server says the codes once,
 * and Android may kill a backgrounded app at any time, so a batch that lived only in a ViewModel could be spent
 * and unprintable after a process death (Copilot's review of PR #5). It is kept here, encrypted like the session
 * under the Keystore key, until the person says they have the slips; then it is gone from the phone too.
 */
interface PendingInvitesStore {
  suspend fun save(issued: IssuedInvites)
  suspend fun load(): IssuedInvites?
  suspend fun clear()
}

@Singleton
class DataStorePendingInvitesStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val cipher: SecretCipher,
  private val json: Json,
) : PendingInvitesStore {
  private val key = stringPreferencesKey("pending_invites")

  @Serializable
  private data class Stored(val batch: String, val label: String?, val expiresAt: String?, val codes: List<String>, val groupName: String, val outstanding: Int, val limit: Int)

  override suspend fun save(issued: IssuedInvites) {
    val stored = Stored(issued.batch, issued.label, issued.expiresAt?.toString(), issued.codes, issued.groupName, issued.outstanding, issued.limit)
    val blob = cipher.encrypt(json.encodeToString(Stored.serializer(), stored).toByteArray(Charsets.UTF_8))
    dataStore.edit { it[key] = Base64.getEncoder().encodeToString(blob) }
  }

  /** Null when nothing is pending; an undecryptable blob (a new Keystore key) reads as nothing pending too. */
  override suspend fun load(): IssuedInvites? = dataStore.data.first()[key]?.let { stored ->
    runCatching {
      val s = json.decodeFromString(Stored.serializer(), String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8))
      IssuedInvites(s.batch, s.label, s.expiresAt?.let { Instant.parse(it) }, s.codes, s.groupName, s.outstanding, s.limit)
    }.getOrNull()
  }

  override suspend fun clear() { dataStore.edit { it.remove(key) } }
}

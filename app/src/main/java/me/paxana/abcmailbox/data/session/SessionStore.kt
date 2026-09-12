package me.paxana.abcmailbox.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the [Session] across process restarts. DataStore replaces
 * SharedPreferences: writes are transactional and reads are a Flow, so the UI
 * observes sign-in state instead of polling it. The stored value is the
 * session's JSON, encrypted by [SecretCipher], base64 for the string key.
 */
@Singleton
class SessionStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val cipher: SecretCipher,
  private val json: Json,
) {
  private val key = stringPreferencesKey("session")

  /** `null` means signed out. An undecryptable blob also reads as signed out. */
  val session: Flow<Session?> = dataStore.data.map { prefs ->
    prefs[key]?.let { stored ->
      runCatching {
        val bytes = cipher.decrypt(Base64.getDecoder().decode(stored))
        json.decodeFromString<Session>(String(bytes, Charsets.UTF_8))
      }.getOrNull()
    }
  }

  suspend fun save(session: Session) {
    val blob = cipher.encrypt(json.encodeToString(session).toByteArray(Charsets.UTF_8))
    val encoded = Base64.getEncoder().encodeToString(blob)
    dataStore.edit { it[key] = encoded }
  }

  suspend fun clear() {
    dataStore.edit { it.remove(key) }
  }
}

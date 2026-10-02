package me.paxana.abcmailbox.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import java.util.Base64
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which usernames this device has signed in to with the split scheme (API PR #114). Once one has, the device
 * refuses to sign in to that name as `plain` whatever the server says: a tampered server cannot then talk a
 * known device into sending the real password. The memory is per device and never cleared by signing out.
 */
interface SchemeMemory {
  suspend fun isKnownSplit(username: String): Boolean
  suspend fun rememberSplit(username: String)
}

@Singleton
class DataStoreSchemeMemory @Inject constructor(private val dataStore: DataStore<Preferences>, private val cipher: SecretCipher) : SchemeMemory {
  // The names are sealed under the Keystore key: which accounts have signed in on this phone is not to be read off
  // it, and the memory outlives sign-outs and deletions (docs/DECISIONS.md), so it is the one thing that would stay
  // readable. The release before this one kept them in the clear under `split_usernames`; that set is folded in and
  // removed the first time either method runs.
  private val sealed = stringPreferencesKey("split_usernames_sealed")
  private val legacy = stringSetPreferencesKey("split_usernames")
  // The server compares usernames case-insensitively, so one name is one entry however it was typed.
  private fun norm(username: String) = username.trim().lowercase()
  private fun open(prefs: Preferences): Set<String> = prefs[sealed]?.let { stored ->
    runCatching { String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toSet() }.getOrNull()
  }.orEmpty()
  private fun MutablePreferences.write(names: Set<String>) {
    this[sealed] = Base64.getEncoder().encodeToString(cipher.encrypt(names.joinToString("\n").toByteArray(Charsets.UTF_8)))
    remove(legacy)
  }
  private suspend fun migrated(): Set<String> {
    val prefs = dataStore.data.first()
    val plain = prefs[legacy] ?: return open(prefs)
    var all = emptySet<String>()
    dataStore.edit { all = open(it) + it[legacy].orEmpty(); it.write(all) }
    return all
  }
  override suspend fun isKnownSplit(username: String) = norm(username) in migrated()
  override suspend fun rememberSplit(username: String) { migrated(); dataStore.edit { it.write(open(it) + norm(username)) } }
}

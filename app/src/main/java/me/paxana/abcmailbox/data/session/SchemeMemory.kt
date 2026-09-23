package me.paxana.abcmailbox.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
class DataStoreSchemeMemory @Inject constructor(private val dataStore: DataStore<Preferences>) : SchemeMemory {
  private val key = stringSetPreferencesKey("split_usernames")
  // The server compares usernames case-insensitively, so one name is one entry however it was typed.
  private fun norm(username: String) = username.trim().lowercase()
  override suspend fun isKnownSplit(username: String) = norm(username) in dataStore.data.first()[key].orEmpty()
  override suspend fun rememberSplit(username: String) { dataStore.edit { it[key] = it[key].orEmpty() + norm(username) } }
}

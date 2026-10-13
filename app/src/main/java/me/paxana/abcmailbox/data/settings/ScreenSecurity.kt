package me.paxana.abcmailbox.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Screen security": whether the app blocks screenshots and screen recording of itself (the window's `FLAG_SECURE`,
 * set by the Activity). Off by default, decided 13 October 2026 (docs/DECISIONS.md): the recovery-code screens tell
 * people to save their codes, and a screenshot is how many will. On, nothing in the app can be captured, by the
 * person or by another app, which is what someone at risk may want. A setting of the phone, not of an account: it is
 * the phone that is looked at.
 */
@Singleton
class ScreenSecurity @Inject constructor(private val dataStore: DataStore<Preferences>) {
  private val key = booleanPreferencesKey("screen_security")
  val enabled: Flow<Boolean> = dataStore.data.map { it[key] ?: false }
  suspend fun set(on: Boolean) { dataStore.edit { it[key] = on } }
}

package me.paxana.abcmailbox.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.IdBody
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push notifications were taken out of the Android app on 27 September 2026: a ring through Google told Google
 * that a phone has this app and when it was rung, which this app's people should not have to trade for speed.
 * The app checks the feed itself instead (every three hours, and whenever it is opened).
 *
 * A phone that had push on still has a device record on the server, which would go on ringing Google for
 * nothing. The first time the app runs signed in, that record is removed, and then the two settings the old
 * switch kept are forgotten. Google's copy of the phone's address goes stale by itself: without the Firebase
 * library the app cannot ask it to forget, and nothing reaches the phone through it any more.
 */
interface PushRetirement {
  /** Removes an old device record from the server, once, whenever someone is signed in. */
  fun start()

  /** The account was deleted, and the server's record with it: only the old settings are left to forget. */
  suspend fun forgetLocally()
}

@Singleton
class DefaultPushRetirement @Inject constructor(
  private val api: NotificationsApi,
  private val sessions: SessionRepository,
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
  @ApplicationScope private val scope: CoroutineScope,
) : PushRetirement {

  // The keys the switch used; nothing writes them any more.
  private val enabledKey = booleanPreferencesKey("push_enabled")
  private val deviceIdKey = intPreferencesKey("push_device_id")

  override fun start() {
    scope.launch {
      // A device record belongs to the session that registered it (signing out already stopped its rings), so it is
      // removed as whoever is signed in; until it is gone, each new sign-in tries again.
      sessions.state.map { it is SessionState.SignedIn }.distinctUntilChanged().takeWhile { hasSettings() }.collect { signedIn ->
        if (signedIn) retire()
      }
    }
  }

  override suspend fun forgetLocally() { dataStore.edit { it.remove(enabledKey); it.remove(deviceIdKey) } }

  internal suspend fun retire() {
    val deviceId = dataStore.data.first()[deviceIdKey] ?: return forgetLocally()
    when (val r = apiCall(json) { api.removeDevice(IdBody(deviceId)) }) {
      is ApiResult.Success -> forgetLocally()
      // Already gone (another session, or the server pruned it): nothing left to remove. No connection: next time.
      is ApiResult.Failure -> if (r.error is AppError.NotFound || r.error is AppError.Forbidden || r.error is AppError.Gone) forgetLocally()
    }
  }

  private suspend fun hasSettings(): Boolean = dataStore.data.first().let { it.contains(enabledKey) || it.contains(deviceIdKey) }
}

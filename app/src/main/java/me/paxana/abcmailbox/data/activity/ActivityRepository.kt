package me.paxana.abcmailbox.data.activity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.domain.Activity
import javax.inject.Inject
import javax.inject.Singleton

/** Shows and clears the system notification. An interface so the repository can be tested on the JVM. */
interface ActivityNotifier {
  /** Whatever the system needs before anything can be shown (on Android: the notification channels). */
  fun prepare() {}
  fun show(fresh: List<Activity>)
  fun clear()
}

interface ActivityRepository {
  /** Entries the account has not read, for the badge on the Inbox tab. */
  val unread: StateFlow<Int>
  /**
   * News that arrived while the app is on screen. Whoever is showing a screen collects this (only while it is
   * visible), and then the news is theirs to present: a line at the bottom of the screen, a list that reloads.
   * When nobody is collecting, nobody is looking, and the news becomes a system notification instead.
   */
  val arrivals: SharedFlow<List<Activity>>
  /**
   * Fetches what is new since this phone last looked and rings for it. Called by the periodic worker, by the
   * push doorbell, and when the app opens. Quiet when signed out or offline: this is housekeeping.
   */
  suspend fun sync(announce: Boolean = true): List<Activity>
  /** The person is looking at their Inbox: everything counts as seen, here and on their other devices. */
  suspend fun markAllRead()
  /** The account was deleted: its place in its feed, the badge, and any notification still showing. */
  suspend fun forget(userId: Int) {}
}

@Singleton
class DefaultActivityRepository @Inject constructor(
  private val api: NotificationsApi,
  private val sessions: SessionRepository,
  private val dataStore: DataStore<Preferences>,
  private val notifier: ActivityNotifier,
  private val json: Json,
) : ActivityRepository {

  private val _unread = MutableStateFlow(0)
  private val _arrivals = MutableSharedFlow<List<Activity>>(extraBufferCapacity = 8)
  override val arrivals: SharedFlow<List<Activity>> = _arrivals.asSharedFlow()
  override val unread: StateFlow<Int> = _unread.asStateFlow()

  private val userId: Int? get() = (sessions.state.value as? SessionState.SignedIn)?.session?.user?.id
  /** Per account: two people sharing a phone each have their own place in their own feed. */
  private fun lastSeenKey(user: Int) = intPreferencesKey("activity_last_seen_$user")

  override suspend fun sync(announce: Boolean): List<Activity> {
    val user = userId ?: run { _unread.value = 0; return emptyList() }
    val since = dataStore.data.first()[lastSeenKey(user)]
    val envelope = when (val r = apiCall(json) { api.feed(since = since, unread = true) }) {
      is ApiResult.Failure -> return emptyList()
      is ApiResult.Success -> r.value
    }
    if (userId != user) return emptyList() // signed out, or someone else signed in, while the request was in flight
    val entries = envelope.data.orEmpty()
    _unread.value = envelope.unread ?: entries.size
    val fresh = entries.filter { it.readAt == null }.map { e ->
      Activity(
        e.id, Activity.kindOf(e.event, runCatching { e.detail?.get("status")?.jsonPrimitive?.contentOrNull }.getOrNull()), e.chat, e.message,
        held = runCatching { e.detail?.get("held")?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        count = (runCatching { e.detail?.get("count")?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1).coerceAtLeast(1),
      )
    }
    entries.maxOfOrNull { it.id }?.let { newest -> dataStore.edit { it[lastSeenKey(user)] = newest } }
    if (announce && fresh.isNotEmpty()) {
      // A banner over the app you are already using is noise, and it would leave the screen underneath stale.
      if (_arrivals.subscriptionCount.value > 0) _arrivals.emit(fresh) else notifier.show(fresh)
    }
    return fresh
  }

  override suspend fun forget(userId: Int) {
    dataStore.edit { it.remove(lastSeenKey(userId)) }
    _unread.value = 0
    notifier.clear()
  }

  override suspend fun markAllRead() {
    if (userId == null) return
    notifier.clear()
    if (_unread.value == 0) return
    if (apiCall(json) { api.markRead() } is ApiResult.Success) _unread.value = 0
  }
}

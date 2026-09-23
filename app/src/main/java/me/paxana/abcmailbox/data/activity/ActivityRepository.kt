package me.paxana.abcmailbox.data.activity

import me.paxana.abcmailbox.data.crypto.GroupKeyring
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
import me.paxana.abcmailbox.data.api.NotificationDto
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
  private val keyring: GroupKeyring,
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
    val all = entries.map { e ->
      e to Activity(
        e.id, Activity.kindOf(e.event, e.detailText("status"), e.detailText("action")), e.chat, e.message,
        held = e.detailInt("held") ?: 0,
        count = (e.detailInt("count") ?: 1).coerceAtLeast(1),
        // "Your copy was withdrawn" and "you are the owner now" read differently from the same news about somebody else.
        aboutMe = (e.detailInt("member") ?: e.detailInt("owner"))?.let { it == user } == true,
        ownerless = e.event == "group.owner" && e.detail?.containsKey("owner") == true && e.detailInt("owner") == null,
      )
    }
    val fresh = all.filter { (e, _) -> e.readAt == null }.map { it.second }
    entries.maxOfOrNull { it.id }?.let { newest -> dataStore.edit { it[lastSeenKey(user)] = newest } }
    if (announce && fresh.isNotEmpty()) {
      // A banner over the app you are already using is noise, and it would leave the screen underneath stale.
      if (_arrivals.subscriptionCount.value > 0) _arrivals.emit(fresh) else notifier.show(fresh)
    }
    // The group's key changed hands or was replaced: what this phone holds may be stale, so it is loaded again. Every
    // fetched entry counts, read or not. (An event another device read before this phone asked is not fetched at all,
    // the query being for unread entries; a key stale that way heals on use, when the server answers KeyVersionError.)
    if (all.any { it.second.touchesGroupKey }) runCatching { keyring.load(force = true) }
    return fresh
  }

  private fun NotificationDto.detailText(key: String): String? = runCatching { detail?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
  private fun NotificationDto.detailInt(key: String): Int? = runCatching { detail?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

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

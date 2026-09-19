package me.paxana.abcmailbox.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.IdBody
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.api.RegisterDeviceRequest
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.session.SessionRepository
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whatever can ring this phone from outside. Today that is Firebase Cloud Messaging; the interface is
 * here so that a build without Google's libraries, or a second provider such as UnifiedPush (the API's
 * sender is pluggable too), is a new implementation and not a rewrite.
 */
interface PushProvider {
  enum class Availability {
    /** This build was made without a Firebase project. */
    NOT_CONFIGURED,
    /** The phone has no Google push service (a de-Googled phone, some tablets, some regions). */
    NO_SERVICE,
    READY,
  }
  val availability: Availability
  /** The address the provider has given this phone, or null if it would not give one. */
  suspend fun token(): String?
  /** Tells the provider to forget this phone. */
  suspend fun forget()
  /** What to call this phone in the account's list of devices. */
  val deviceLabel: String
}

/**
 * Whether this phone may be rung, and keeping the server's record of it fresh.
 *
 * **Off unless the person turns it on.** A push passes through Google, and although it carries nothing
 * (the payload is `{"type":"sync"}` for every event), Google still learns that this phone has this app
 * and when it is rung. For people who write to political prisoners that is theirs to decide, with the
 * trade explained. Without it the app checks the feed a few times a day and whenever it is opened.
 */
interface PushRegistrar {
  val availability: PushProvider.Availability
  val enabled: Flow<Boolean>
  /** Success carries whether the server can actually deliver pushes today (`deliverable`). */
  suspend fun turnOn(): ApiResult<Boolean>
  suspend fun turnOff(): ApiResult<Unit>
  /** The provider issued a new address for this phone. */
  fun onNewToken(token: String)
}

@Singleton
class DefaultPushRegistrar @Inject constructor(
  private val provider: PushProvider,
  private val api: NotificationsApi,
  private val sessions: SessionRepository,
  private val dataStore: DataStore<Preferences>,
  private val json: Json,
  @ApplicationScope private val scope: CoroutineScope,
) : PushRegistrar {

  private val enabledKey = booleanPreferencesKey("push_enabled")
  private val deviceIdKey = intPreferencesKey("push_device_id")

  override val availability get() = provider.availability
  override val enabled: Flow<Boolean> = dataStore.data.map { it[enabledKey] == true }.distinctUntilChanged()

  init {
    // The API asks for a registration after every sign-in and after a password change (which replaces the
    // session): both show up here as a new token in the session state. A token belongs to one account at a
    // time, so a different person signing in on this phone moves it to them.
    scope.launch {
      sessions.state.map { (it as? SessionState.SignedIn)?.session?.token }.distinctUntilChanged().collect { token ->
        if (token != null && dataStore.data.first()[enabledKey] == true) register()
      }
    }
  }

  override suspend fun turnOn(): ApiResult<Boolean> {
    if (provider.availability != PushProvider.Availability.READY) return ApiResult.Failure(AppError.Unexpected(IllegalStateException("push is not available: ${provider.availability}")))
    return when (val r = register()) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> { dataStore.edit { it[enabledKey] = true }; r }
    }
  }

  override suspend fun turnOff(): ApiResult<Unit> {
    // Locally first: whatever the network does next, this phone has stopped asking to be rung.
    val deviceId = dataStore.data.first()[deviceIdKey]
    dataStore.edit { it[enabledKey] = false; it.remove(deviceIdKey) }
    runCatching { provider.forget() }
    return if (deviceId == null) ApiResult.Success(Unit) else apiCall(json) { api.removeDevice(IdBody(deviceId)) }.map { }
  }

  override fun onNewToken(token: String) {
    scope.launch { if (dataStore.data.first()[enabledKey] == true && sessions.state.value is SessionState.SignedIn) register(token) }
  }

  private suspend fun register(known: String? = null): ApiResult<Boolean> {
    val token = known ?: runCatching { provider.token() }.getOrNull()
      ?: return ApiResult.Failure(AppError.Unexpected(IllegalStateException("the push service gave this phone no address")))
    return when (val r = apiCall(json) { api.registerDevice(RegisterDeviceRequest(token = token, label = provider.deviceLabel)) }) {
      is ApiResult.Failure -> r
      is ApiResult.Success -> {
        val device = r.value.data
        device?.id?.let { id -> dataStore.edit { it[deviceIdKey] = id } }
        ApiResult.Success(device?.deliverable == true)
      }
    }
  }
}

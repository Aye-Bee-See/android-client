package me.paxana.abcmailbox.data.dev

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.HealthApi
import me.paxana.abcmailbox.data.api.apiCall
import me.paxana.abcmailbox.data.api.map
import me.paxana.abcmailbox.data.crypto.EncryptionModeRepository
import me.paxana.abcmailbox.data.crypto.KeyVault
import me.paxana.abcmailbox.data.session.SessionStore
import me.paxana.abcmailbox.di.ApplicationScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The URL the interceptor reads. Separate from the repository so OkHttp does
 * not depend on something that depends on Retrofit (which depends on OkHttp).
 */
@Singleton
class DevServerUrl @Inject constructor() {
  @Volatile private var value: String = BuildConfig.API_BASE_URL
  fun current(): String = value
  fun update(url: String) { value = url }
}

/**
 * A developer override for the API address, so a phone on the same Wi-Fi can
 * talk to the API on a development machine. Stored in DataStore; `null`
 * means the build's default. Only the debug Account screen exposes it.
 */
@Singleton
class DevServerRepository @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val sessionStore: SessionStore,
  private val healthApi: HealthApi,
  private val json: Json,
  private val holder: DevServerUrl,
  private val vault: KeyVault,
  private val modes: EncryptionModeRepository,
  @ApplicationScope scope: CoroutineScope,
) {
  private val key = stringPreferencesKey("dev_api_base_url")

  val default: String = BuildConfig.API_BASE_URL

  /** The URL in force; each value is also pushed into the holder the interceptor reads. */
  val baseUrl: StateFlow<String> = dataStore.data
    .map { it[key]?.takeIf { url -> url.toHttpUrlOrNull() != null } ?: default }
    .map { it.also(holder::update) }
    .stateIn(scope, SharingStarted.Eagerly, default)

  val isOverridden: Boolean get() = baseUrl.value != default

  /** Normalises the input, saves it, and signs out (a token is only good for the server that issued it). */
  suspend fun set(input: String): Result<String> {
    val url = normalise(input) ?: return Result.failure(IllegalArgumentException("That is not a valid URL. Try http://192.168.1.20:3000/"))
    dataStore.edit { it[key] = url }
    holder.update(url)
    sessionStore.clear()
    vault.clear()
    modes.refresh()
    return Result.success(url)
  }

  suspend fun reset() {
    dataStore.edit { it.remove(key) }
    holder.update(default)
    sessionStore.clear()
    vault.clear()
    modes.refresh()
  }

  /** `GET /health` at whatever URL is in force; the interceptor applies it. */
  suspend fun check(): ApiResult<String> = apiCall(json) { healthApi.health() }
    .map { "${it.status ?: "?"}, ${it.encryptionMode ?: "mode unknown"} mode" }

  companion object {
    /** Accepts "192.168.1.20", "192.168.1.20:3000", "http://host:3000" and returns "http://host:3000/". */
    fun normalise(input: String): String? {
      var s = input.trim()
      if (s.isEmpty()) return null
      val schemeGiven = s.startsWith("http://") || s.startsWith("https://")
      if (!schemeGiven) s = "http://$s"
      val url = s.toHttpUrlOrNull() ?: return null
      // A bare host ("192.168.1.20") means the dev API on its default port; an explicit
      // scheme is taken literally, so https://api.example.net stays on 443.
      val portGiven = s.removePrefix("${url.scheme}://").substringBefore('/').contains(":")
      val withPort = if (!schemeGiven && !portGiven) url.newBuilder().port(3000).build() else url
      return withPort.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }
  }
}

/**
 * Rewrites every request's scheme, host, and port to the configured base URL,
 * keeping the path and query. Retrofit's own base URL becomes just a template.
 */
class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val target = baseUrl().toHttpUrlOrNull() ?: return chain.proceed(chain.request())
    val original = chain.request()
    val rewritten = original.url.newBuilder()
      .scheme(target.scheme)
      .host(target.host)
      .port(target.port)
      .build()
    return chain.proceed(original.newBuilder().url(rewritten).build())
  }
}

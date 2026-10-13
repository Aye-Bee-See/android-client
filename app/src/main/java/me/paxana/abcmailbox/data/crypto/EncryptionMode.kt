package me.paxana.abcmailbox.data.crypto

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.HealthApi
import me.paxana.abcmailbox.data.api.apiCall
import javax.inject.Inject
import javax.inject.Singleton

enum class EncryptionMode { UNKNOWN, SERVER, E2E }

/**
 * `/health` could not be asked, so which contract the server speaks is not known. Carried as the network failure it
 * is ([me.paxana.abcmailbox.data.api.AppError.Network]): nothing that depends on the mode may guess "server mode",
 * because a letter sent in the clear on that guess would reach an end-to-end server as plain text.
 */
class EncryptionModeUnknownException : java.io.IOException("The server's encryption mode could not be learnt.")

interface EncryptionModeRepository {
  val mode: StateFlow<EncryptionMode>
  /**
   * The server once spoke end-to-end and now says server mode (decided 13 October 2026, docs/DECISIONS.md): nothing
   * is sent to it in the clear, and the person is told. A move the other way is followed; this one is refused.
   */
  val downgraded: StateFlow<Boolean> get() = NEVER_DOWNGRADED
  /** Asks `/health`. Keeps the last known mode if the server cannot be reached. */
  suspend fun refresh(): EncryptionMode
  /** The known mode, asking the server first if it is not known yet. */
  suspend fun current(): EncryptionMode = mode.value.takeIf { it != EncryptionMode.UNKNOWN } ?: refresh()

  companion object { internal val NEVER_DOWNGRADED: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow() }
}

/**
 * The server says which letter contract it speaks (`GET /health`, API PR #80),
 * so one build of the app works against both modes: it asks once per launch
 * and again whenever the developer server override changes.
 */
@Singleton
class DefaultEncryptionModeRepository @Inject constructor(
  private val healthApi: HealthApi,
  private val json: Json,
  private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
  private val server: me.paxana.abcmailbox.data.dev.DevServerUrl,
) : EncryptionModeRepository {
  private val _mode = MutableStateFlow(EncryptionMode.UNKNOWN)
  override val mode: StateFlow<EncryptionMode> = _mode.asStateFlow()
  private val _downgraded = MutableStateFlow(false)
  override val downgraded: StateFlow<Boolean> = _downgraded.asStateFlow()

  // This phone remembers that a server spoke end-to-end, per server address and across restarts, as it remembers which
  // names sign in without their password (SchemeMemory): the one thing a server must not be able to talk it out of.
  private fun wasEndToEnd() = androidx.datastore.preferences.core.booleanPreferencesKey("was_e2e|" + server.current().lowercase().trimEnd('/'))

  override suspend fun refresh(): EncryptionMode {
    val r = apiCall(json) { healthApi.health() }
    if (r is ApiResult.Success) {
      when (r.value.encryptionMode) {
        "e2e" -> {
          _mode.value = EncryptionMode.E2E
          _downgraded.value = false
          dataStore.edit { it[wasEndToEnd()] = true }
        }
        "server" -> {
          _mode.value = EncryptionMode.SERVER
          _downgraded.value = dataStore.data.first()[wasEndToEnd()] == true
        }
        else -> Unit
      }
    }
    return _mode.value
  }
}

package me.paxana.abcmailbox.data.crypto

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

interface EncryptionModeRepository {
  val mode: StateFlow<EncryptionMode>
  /** Asks `/health`. Keeps the last known mode if the server cannot be reached. */
  suspend fun refresh(): EncryptionMode
  /** The known mode, asking the server first if it is not known yet. */
  suspend fun current(): EncryptionMode = mode.value.takeIf { it != EncryptionMode.UNKNOWN } ?: refresh()
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
) : EncryptionModeRepository {
  private val _mode = MutableStateFlow(EncryptionMode.UNKNOWN)
  override val mode: StateFlow<EncryptionMode> = _mode.asStateFlow()

  override suspend fun refresh(): EncryptionMode {
    val r = apiCall(json) { healthApi.health() }
    if (r is ApiResult.Success) {
      _mode.value = when (r.value.encryptionMode) {
        "e2e" -> EncryptionMode.E2E
        "server" -> EncryptionMode.SERVER
        else -> _mode.value
      }
    }
    return _mode.value
  }
}

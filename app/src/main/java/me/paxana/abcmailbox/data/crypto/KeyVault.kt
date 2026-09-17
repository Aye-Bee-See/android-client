package me.paxana.abcmailbox.data.crypto

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.crypto.Sodium
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.di.ApplicationScope
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** The account's unwrapped keypair, for whoever is signed in. */
interface KeyVault {
  /** The user id whose key is loaded, or null when locked. */
  val unlockedFor: StateFlow<Int?>
  fun keyPair(userId: Int): Sodium.KeyPair?
  suspend fun store(userId: Int, keyPair: Sodium.KeyPair)
  suspend fun clear()
}

/**
 * Keeps the private key in memory and, so the writer is not asked for their
 * password on every launch, a copy on disk encrypted with the Android
 * Keystore key (the brief allows Keystore-backed storage, never plain disk).
 * Signing out clears both.
 */
@Singleton
class DefaultKeyVault @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val cipher: SecretCipher,
  @ApplicationScope scope: CoroutineScope,
) : KeyVault {

  @Serializable
  private data class Stored(val userId: Int, val publicKey: String, val privateKey: String)

  private val key = stringPreferencesKey("key_vault")
  private val _unlockedFor = MutableStateFlow<Int?>(null)
  override val unlockedFor: StateFlow<Int?> = _unlockedFor.asStateFlow()
  @Volatile private var current: Pair<Int, Sodium.KeyPair>? = null

  init {
    scope.launch {
      val stored = dataStore.data.first()[key] ?: return@launch
      runCatching {
        val s = Json.decodeFromString<Stored>(String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8))
        val d = Base64.getDecoder()
        current = s.userId to Sodium.KeyPair(d.decode(s.publicKey), d.decode(s.privateKey))
        _unlockedFor.value = s.userId
      }
    }
  }

  override fun keyPair(userId: Int): Sodium.KeyPair? = current?.takeIf { it.first == userId }?.second

  override suspend fun store(userId: Int, keyPair: Sodium.KeyPair) {
    current = userId to keyPair
    _unlockedFor.value = userId
    val e = Base64.getEncoder()
    val blob = cipher.encrypt(Json.encodeToString(Stored(userId, e.encodeToString(keyPair.publicKey), e.encodeToString(keyPair.privateKey))).toByteArray(Charsets.UTF_8))
    dataStore.edit { it[key] = e.encodeToString(blob) }
  }

  override suspend fun clear() {
    current?.second?.privateKey?.fill(0)
    current = null
    _unlockedFor.value = null
    dataStore.edit { it.remove(key) }
  }
}

package me.paxana.abcmailbox.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.session.SecretCipher
import me.paxana.abcmailbox.domain.IssuedInvites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A batch of invite codes waits for its own account, and one the release before kept for whoever is not lost. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePendingInvitesStoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private object Reversing : SecretCipher { override fun encrypt(plain: ByteArray) = plain.reversedArray(); override fun decrypt(blob: ByteArray) = blob.reversedArray() }
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") } }
  private val json = Json { ignoreUnknownKeys = true }
  private val batch = IssuedInvites("b1", "Letter night", null, listOf("7Q4M2XKD9HBT"), "Portland ABC", 1, 20)

  @Test
  fun `a batch is the account's that issued it, and no other's`() = runTest {
    val pending = DataStorePendingInvitesStore(store, Reversing, json)
    pending.save(9, batch)
    assertEquals(batch, pending.load(9)); assertNull("another admin on the same phone does not see it", pending.load(10))
    pending.clear(9); assertNull(pending.load(9))
  }

  @Test
  fun `a batch the release before kept for whoever becomes the first account's to look, and the old key goes`() = runTest {
    val pending = DataStorePendingInvitesStore(store, Reversing, json)
    pending.save(9, batch)
    val blob = store.data.first()[stringPreferencesKey("pending_invites_9")]!!
    store.edit { it.remove(stringPreferencesKey("pending_invites_9")); it[stringPreferencesKey("pending_invites")] = blob }
    assertEquals(batch, pending.load(9))
    assertNull(store.data.first()[stringPreferencesKey("pending_invites")])
    assertEquals("moved, not copied", batch, pending.load(9)); assertNull(pending.load(10))
  }
}

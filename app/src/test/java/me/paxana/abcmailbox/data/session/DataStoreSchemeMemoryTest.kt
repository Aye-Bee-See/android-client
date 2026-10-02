package me.paxana.abcmailbox.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Which names have signed in as split is kept, but not readable off the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSchemeMemoryTest {
  @get:Rule val tmp = TemporaryFolder()
  private object Reversing : SecretCipher { override fun encrypt(plain: ByteArray) = plain.reversedArray(); override fun decrypt(blob: ByteArray) = blob.reversedArray() }
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") } }

  @Test
  fun `a name remembered is known however it is typed, and the file holds no name in the clear`() = runTest {
    val memory = DataStoreSchemeMemory(store, Reversing)
    memory.rememberSplit(" Carol ")
    assertTrue(memory.isKnownSplit("carol")); assertFalse(memory.isKnownSplit("dave"))
    val prefs = store.data.first()
    assertNull("the old plain set is not written", prefs[stringSetPreferencesKey("split_usernames")])
    assertFalse("sealed", prefs[stringPreferencesKey("split_usernames_sealed")]!!.contains("carol"))
  }

  @Test
  fun `names the release before kept in the clear are folded in and the clear copy removed`() = runTest {
    store.edit { it[stringSetPreferencesKey("split_usernames")] = setOf("alex", "sam") }
    val memory = DataStoreSchemeMemory(store, Reversing)
    assertTrue(memory.isKnownSplit("Alex"))
    assertNull("gone from the clear", store.data.first()[stringSetPreferencesKey("split_usernames")])
    memory.rememberSplit("carol")
    assertTrue(memory.isKnownSplit("sam")); assertTrue(memory.isKnownSplit("carol"))
    assertEquals(setOf("alex", "sam", "carol"), String(Reversing.decrypt(java.util.Base64.getDecoder().decode(store.data.first()[stringPreferencesKey("split_usernames_sealed")]!!))).lines().toSet())
  }
}

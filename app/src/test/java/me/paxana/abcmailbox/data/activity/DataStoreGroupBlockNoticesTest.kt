package me.paxana.abcmailbox.data.activity

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A group's name and its words about a writer are kept sealed, and a notice written in the clear by the release before is still read. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreGroupBlockNoticesTest {
  @get:Rule val tmp = TemporaryFolder()
  private object Reversing : SecretCipher { override fun encrypt(plain: ByteArray) = plain.reversedArray(); override fun decrypt(blob: ByteArray) = blob.reversedArray() }
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") } }
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `a notice is kept sealed, read back, and lifted`() = runTest {
    val notices = DataStoreGroupBlockNotices(store, json, Reversing)
    notices.blocked(2, 7, GroupBlockNotice("Portland ABC", "letters came back twice"))
    assertEquals(mapOf(7 to GroupBlockNotice("Portland ABC", "letters came back twice")), notices.notices(2).first())
    val raw = store.data.first()[stringPreferencesKey("group_block_notices_2")]!!
    assertFalse("neither the group nor its reason is in the clear", raw.contains("Portland") || raw.contains("twice"))
    notices.lifted(2, 7)
    assertEquals(emptyMap<Int, GroupBlockNotice>(), notices.notices(2).first())
  }

  @Test
  fun `a notice the release before wrote in the clear is still read, and sealed at the next change`() = runTest {
    store.edit { it[stringPreferencesKey("group_block_notices_2")] = """{"7":{"groupName":"Portland ABC","reason":"old"}}""" }
    val notices = DataStoreGroupBlockNotices(store, json, Reversing)
    assertEquals(GroupBlockNotice("Portland ABC", "old"), notices.notices(2).first()[7])
    notices.blocked(2, 8, GroupBlockNotice("Riverside", "new"))
    assertFalse(store.data.first()[stringPreferencesKey("group_block_notices_2")]!!.contains("Portland"))
    assertEquals(setOf(7, 8), notices.notices(2).first().keys)
  }
}

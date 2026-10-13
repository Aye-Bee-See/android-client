package me.paxana.abcmailbox.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Screen security is off until the person switches it on, and the choice is kept. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenSecurityTest {
  @get:Rule val tmp = TemporaryFolder()
  private val scope = TestScope(UnconfinedTestDispatcher())
  private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") } }

  @Test
  fun `off by default, and on once chosen`() = runTest {
    val setting = ScreenSecurity(store)
    assertEquals(false, setting.enabled.first())
    setting.set(true)
    assertEquals(true, setting.enabled.first())
    assertEquals("a second reader of the same store sees the choice", true, ScreenSecurity(store).enabled.first())
    setting.set(false)
    assertEquals(false, setting.enabled.first())
  }
}

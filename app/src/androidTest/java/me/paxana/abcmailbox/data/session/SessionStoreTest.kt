package me.paxana.abcmailbox.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a device: the Android Keystore has no JVM equivalent. Proves that a
 * session survives a round trip through the encrypted store and that the
 * stored value is not the plaintext token.
 */
@RunWith(AndroidJUnit4::class)
class SessionStoreTest {

  @Test
  fun roundTripThroughKeystore() = runTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "session-test-${System.nanoTime()}.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(scope = this) { file }
    val store = SessionStore(dataStore, KeystoreSecretCipher(), Json)

    assertNull(store.session.first())

    val session = Session("secret-token", 123L, SessionUser(7, "user7", "Seven", null, "user", null))
    store.save(session)
    assertEquals(session, store.session.first())

    val raw = file.readBytes().toString(Charsets.ISO_8859_1)
    assert(!raw.contains("secret-token")) { "token stored in plaintext" }

    store.clear()
    assertNull(store.session.first())
    file.delete()
  }
}

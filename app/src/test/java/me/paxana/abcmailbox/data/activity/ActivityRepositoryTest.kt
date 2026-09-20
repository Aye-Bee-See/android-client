package me.paxana.abcmailbox.data.activity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import me.paxana.abcmailbox.data.api.NotificationsApi
import me.paxana.abcmailbox.data.session.SessionUser
import me.paxana.abcmailbox.di.NetworkModule
import me.paxana.abcmailbox.domain.Activity
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.text.TestStrings
import me.paxana.abcmailbox.ui.auth.FakeSessionRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

class ActivityRepositoryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val json = NetworkModule.json()
  private val sessions = FakeSessionRepository()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val shown = mutableListOf<List<Activity>>(); private var cleared = 0
  private lateinit var repo: DefaultActivityRepository

  @Before
  fun setUp() {
    server.start()
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(NotificationsApi::class.java)
    val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") }
    repo = DefaultActivityRepository(api, sessions, store, object : ActivityNotifier { override fun show(fresh: List<Activity>) { shown += fresh }; override fun clear() { cleared++ } }, json)
    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
  }

  @After fun tearDown() { server.shutdown(); scope.cancel() }

  // Captured from the live API on 19 Sep 2026, after a member marked a letter printed.
  private fun feed(vararg entries: String, unread: Int = entries.size) = MockResponse().setBody("""{"data":[${entries.joinToString(",")}],"total":${entries.size},"page":1,"page_size":50,"unread":$unread,"success":true,"status":200,"name":"notification many"}""")
  private fun entry(id: Int, event: String, detail: String = "null", chat: Int? = 41, read: Boolean = false) =
    """{"id":$id,"event":"$event","chat":$chat,"message":50,"submission":null,"detail":$detail,"readAt":${if (read) "\"2026-09-19T10:00:00.000Z\"" else "null"},"createdAt":"2026-09-19T09:40:00.000Z"}"""

  @Test
  fun `new entries ring once, and the next look asks only for what came after`() = runTest {
    server.enqueue(feed(entry(3, "letter.reply"), entry(2, "letter.status", """{"status":"printed"}""")))
    val fresh = repo.sync()
    assertEquals("/auth/notifications?unread=true&page_size=50", server.next().path)
    assertEquals(listOf(Activity.Kind.REPLY, Activity.Kind.PRINTED), fresh.map { it.kind })
    assertEquals(1, shown.size); assertEquals(2, repo.unread.value)

    server.enqueue(feed(unread = 2))
    assertTrue(repo.sync().isEmpty())
    assertEquals("/auth/notifications?since=3&unread=true&page_size=50", server.next().path)
    assertEquals("nothing new, nothing rung", 1, shown.size)
  }

  @Test
  fun `with the app on screen the news goes to the screen, and no system notification is posted`() = runTest {
    // The shell subscribes only while the app is visible; this stands in for it.
    val seen = mutableListOf<List<Activity>>()
    val attached = CompletableDeferred<Unit>() // `launch` only promises to start soon; wait until it has
    val watching = scope.launch { repo.arrivals.onSubscription { attached.complete(Unit) }.collect { seen += it } }
    attached.await()

    server.enqueue(feed(entry(4, "letter.reply")))
    repo.sync()
    withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }
    assertEquals(listOf(Activity.Kind.REPLY), seen.single().map { it.kind })
    assertTrue("somebody is looking: no banner over their own screen", shown.isEmpty())

    // The app leaves the screen: the same news now has to reach them another way.
    watching.cancelAndJoin()
    server.enqueue(feed(entry(5, "letter.status", """{"status":"mailed"}""")))
    repo.sync()
    assertEquals(listOf(Activity.Kind.MAILED), shown.single().map { it.kind })
  }

  @Test
  fun `the words are chosen on the phone, name nobody, and come in the user's language`() {
    val mailed = Activity(5, Activity.kindOf("letter.status", "mailed"), 41, 50)
    assertEquals("One of your letters is in the post.", mailed.sentence(TestStrings()))
    assertEquals("Una de tus cartas ya está en el correo.", mailed.sentence(TestStrings("es")))
    assertEquals("Одно из ваших писем отправлено почтой.", mailed.sentence(TestStrings("ru")))
    assertEquals("A letter is waiting for your group to print it.", Activity(6, Activity.kindOf("letter.queued", null), 41, 50).sentence(TestStrings()))
    assertEquals("A change you proposed to the directory was not accepted.", Activity(7, Activity.kindOf("submission.decided", "rejected"), null, null).sentence(TestStrings()))
    // An event a later API adds still rings, with words that promise nothing in particular.
    assertEquals("There is something new in your account.", Activity(8, Activity.kindOf("letter.returned", null), 41, 50).sentence(TestStrings()))
  }

  @Test
  fun `opening the app looks quietly, and opening the inbox marks everything read and clears the notification`() = runTest {
    server.enqueue(feed(entry(3, "letter.reply")))
    repo.sync(announce = false)
    assertTrue("the badge is enough for someone who is already in the app", shown.isEmpty())
    assertEquals(1, repo.unread.value)

    server.takeRequest()
    server.enqueue(MockResponse().setBody("""{"data":{"marked":1,"unread":0},"success":true,"status":200}"""))
    repo.markAllRead()
    val put = server.next()
    assertEquals("PUT", put.method); assertEquals("/auth/notifications/read", put.path); assertEquals("{}", put.body.readUtf8())
    assertEquals(0, repo.unread.value); assertEquals(1, cleared)
  }

  @Test
  fun `signed out there is nothing to fetch, and each account keeps its own place in its own feed`() = runTest {
    sessions.logout()
    assertTrue(repo.sync().isEmpty()); assertEquals(0, server.requestCount)

    sessions.signInAs(SessionUser(2, "user1", null, null, "user", null))
    server.enqueue(feed(entry(9, "letter.reply")))
    repo.sync(); server.next()
    sessions.signInAs(SessionUser(3, "user2", null, null, "user", null))
    server.enqueue(feed())
    repo.sync()
    assertEquals("user2 has never looked, so no `since`", "/auth/notifications?unread=true&page_size=50", server.next().path)
  }

  @Test
  fun `no connection is not news`() = runTest {
    server.shutdown()
    assertTrue(repo.sync().isEmpty()); assertTrue(shown.isEmpty())
  }
}

package me.paxana.abcmailbox.data.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.SchemeDispatcher
import me.paxana.abcmailbox.apiRequestCount
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.api.AuthApi
import me.paxana.abcmailbox.data.crypto.EncryptionMode
import me.paxana.abcmailbox.data.crypto.FakeCryptoEngine
import me.paxana.abcmailbox.data.crypto.FixedMode
import me.paxana.abcmailbox.data.crypto.InMemoryVault
import me.paxana.abcmailbox.domain.GroupInvitation
import me.paxana.abcmailbox.domain.InvitationAccepted
import me.paxana.abcmailbox.domain.NewGroupProfile
import me.paxana.abcmailbox.next
import me.paxana.abcmailbox.queue
import me.paxana.abcmailbox.text.TestStrings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Accepting an invitation (README, "Invitations"): a group admin account whose keys are made on this phone, as on a
 * join, and for a `group` invitation the new group's profile, limited to the fields the invitation allows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvitationTest {
  private val server = MockWebServer()
  private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
  private val vault = InMemoryVault()
  private val memory = FakeSchemeMemory()
  private val store = object : SessionStore {
    val flow = MutableStateFlow<Session?>(null)
    override val session: Flow<Session?> = flow
    override suspend fun save(session: Session) { flow.value = session }
    override suspend fun clear() { flow.value = null }
  }
  private lateinit var repo: DefaultSessionRepository

  @Before fun setUp() { server.start(); server.dispatcher = SchemeDispatcher(SchemeDispatcher.split()) }
  @After fun tearDown() = server.shutdown()

  private fun build(mode: EncryptionMode = EncryptionMode.E2E): DefaultSessionRepository {
    val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AuthApi::class.java)
    return DefaultSessionRepository(store, api, SessionCache(), json, FixedMode(mode), FakeCryptoEngine(), vault, TestScope(UnconfinedTestDispatcher()), TestStrings(), memory).also { repo = it }
  }
  private val token = "7K2M9QX4T8VB3N6Y1RZC5WDH"
  private fun accepted(activation: String, group: String = "Test Chapter") = """{"data":{"user":{"id":9,"username":"rae","role":"chapter"},"chapter":{"id":4,"name":"$group","accountStatus":"${if (activation == "immediate") "active" else "pending"}"},"activation":"$activation"},"success":true,"status":201}"""
  private fun login(keys: String) = """{"data":{"user":{"id":9,"username":"rae","role":"chapter"},"token":{"token":"jwt-9","expires":1},"keys":$keys},"success":true,"status":200}"""
  private fun splitKeys(password: String, salt: String = "SALT") = """{"publicKey":"PUB-NEW","wrappedPrivateKey":"wrapped(PUB-NEW)underwrap($password)with($salt)","kdfSalt":"$salt","kdfParams":{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864},"hasRecovery":true,"orgKey":null}"""
  private fun body() = json.parseToJsonElement(server.next().body.readUtf8()).jsonObject
  private fun field(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]?.jsonPrimitive?.content
  private val allFields = setOf("name", "location", "subregion", "country", "about", "website", "email", "socialLinks", "services", "announcement", "networkRole")

  @Test
  fun `the check says what the token invites to, and a kind this release does not know is refused`() = runTest {
    build()
    server.queue(MockResponse().setBody("""{"data":{"kind":"group","inviteeName":"Riverside ABC","chapter":{"id":1,"name":"Test Chapter"},"expiresAt":"2026-10-01T12:00:00.000Z","activation":"admin_review","groupFields":["name","location","country"]},"success":true,"status":200}"""))
    val inv = (repo.invitationInfo(token) as ApiResult.Success).value
    assertEquals("/invitation/invitation?token=$token", server.next().path)
    assertEquals(GroupInvitation.Kind.GROUP, inv.kind); assertEquals("Riverside ABC", inv.inviteeName); assertEquals("Test Chapter", inv.groupName)
    assertFalse(inv.activatesAtOnce); assertEquals(setOf("name", "location", "country"), inv.groupFields)
    assertEquals("2026-10-01T12:00:00Z", inv.expiresAt.toString())

    // An admin's invitation with nobody vouching: no group to name.
    server.queue(MockResponse().setBody("""{"data":{"kind":"member","inviteeName":"Rae","chapter":null,"activation":"immediate"},"success":true,"status":200}"""))
    val member = (repo.invitationInfo(token) as ApiResult.Success).value
    assertEquals(GroupInvitation.Kind.MEMBER, member.kind); assertNull(member.groupName); assertTrue(member.activatesAtOnce)

    server.queue(MockResponse().setBody("""{"data":{"kind":"partner","inviteeName":"?"},"success":true,"status":200}"""))
    assertTrue((repo.invitationInfo(token) as ApiResult.Failure).error is AppError.Validation)

    server.queue(MockResponse().setResponseCode(410).setBody("""{"success":false,"name":"InvitationError","info":"This invitation has expired.","status":410,"condition":"expired"}"""))
    assertEquals("expired", ((repo.invitationInfo(token) as ApiResult.Failure).error as AppError.Gone).condition)
  }

  @Test
  fun `accepting a member invitation sends the keys made here and no group, then signs in and queues the code`() = runTest {
    build()
    server.queue(MockResponse().setBody(accepted("immediate")))
    server.queue(MockResponse().setBody(login(splitKeys("longenough1"))))
    val r = repo.acceptInvitation(token, " rae ", "longenough1", " rae@example.org ", "", group = null, groupFields = emptySet())
    assertEquals(ApiResult.Success(InvitationAccepted("Test Chapter", activeNow = true)), r)
    val sent = body()
    assertEquals(token, field(sent, "token")); assertEquals("rae", field(sent, "username")); assertEquals("rae@example.org", field(sent, "email"))
    assertNull("an empty name is not sent", sent["name"]); assertNull("a member invitation carries no group", sent["group"])
    assertEquals("split", field(sent, "authScheme")); assertEquals("the auth key, never the password", "auth(longenough1)with(salt-1)", field(sent, "password"))
    assertEquals("PUB-NEW", field(sent, "publicKey")); assertEquals("wrapped(PUB-NEW)under(NEWCODE)", field(sent, "recoveryWrappedPrivateKey"))
    assertEquals("/auth/login", server.next().path)
    assertEquals("jwt-9", store.flow.value?.token); assertNotNull(vault.keyPair(9))
    assertEquals("NEWCODE", repo.pendingRecoveryCode.value)
  }

  @Test
  fun `a group invitation sends the profile, only the fields it allows, nothing blank, and says it waits for review`() = runTest {
    build()
    server.queue(MockResponse().setBody(accepted("admin_review", group = "Riverside ABC")))
    server.queue(MockResponse().setBody(login(splitKeys("longenough1"))))
    val profile = NewGroupProfile(name = " Riverside ABC ", city = "Riverside", region = "Ontario", country = "Canada", about = "", website = "https://riverside.example", services = setOf("letter_writing_nights"), networkRole = "both")
    val r = repo.acceptInvitation(token, "rae", "longenough1", "rae@example.org", "Rae", profile, groupFields = allFields - "website")
    assertEquals(ApiResult.Success(InvitationAccepted("Riverside ABC", activeNow = false)), r)
    val group = body()["group"]!!.jsonObject
    assertEquals("""{"name":"Riverside ABC","location":{"city":"Riverside","region":"Ontario"},"subregion":"Ontario","country":"Canada","services":["letter_writing_nights"],"networkRole":"both"}""", group.toString())
  }

  @Test
  fun `a refused acceptance is handed back as it is, and nothing is signed in`() = runTest {
    build()
    server.queue(MockResponse().setResponseCode(400).setBody("""{"success":false,"name":"ValidationError","info":"Username taken.","errors":["Username taken."],"status":400}"""))
    val r = repo.acceptInvitation(token, "rae", "longenough1", "rae@example.org", null, null, emptySet()) as ApiResult.Failure
    assertTrue(r.error is AppError.Validation)
    assertNull(store.flow.value); assertNull(repo.pendingRecoveryCode.value)
    assertEquals(1, server.apiRequestCount)
  }
}

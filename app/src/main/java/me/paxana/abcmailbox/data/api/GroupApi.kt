package me.paxana.abcmailbox.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * What a support group member does: the print queue, status moves, and the
 * writers the group looks after. Every call needs a `chapter` account whose
 * group is active; otherwise the API answers 403 with a sentence that says
 * which condition failed, and the app shows that sentence as is.
 */
interface GroupApi {

  /** The print queue is "letters my group relays, in this status". */
  @GET("messaging/messages")
  suspend fun relayed(
    @Query("relayChapter") relayChapter: Int,
    @Query("status") status: String? = null,
    @Query("page") page: Int = 1,
    @Query("page_size") pageSize: Int = 20,
  ): ApiEnvelope<List<MessageDto>>

  /** Forward only: queued, printed, mailed. Anything else is a 409 whose `error` says why. */
  @PUT("messaging/status")
  suspend fun setStatus(@Body body: StatusRequest): ApiEnvelope<MessageDto>

  @GET("auth/writers")
  suspend fun writers(@Query("page_size") pageSize: Int = 100): ApiEnvelope<List<WriterDto>>

  @POST("auth/writer")
  suspend fun addWriter(@Body body: AddWriterRequest): ApiEnvelope<WriterDto>

  /**
   * Server mode: send `{writer}` and the token comes back once. End-to-end: the
   * token is made on the device and only its hash and the key wrapped under it
   * are sent, so the answer carries just `expiresAt`. Regenerating replaces the previous one.
   */
  @POST("auth/writer/token")
  suspend fun issueToken(@Body body: IssueTokenRequest): ApiEnvelope<IssuedTokenDto>

  @HTTP(method = "DELETE", path = "auth/writer/token", hasBody = true)
  suspend fun revokeToken(@Body body: WriterRef): ApiEnvelope<JsonElement>

  // The group's own keypair (end-to-end mode only) ---------------------------------------

  /** Once per group: publish its public key, with the private key sealed to the member setting it up. */
  @PUT("auth/chapter-keys")
  suspend fun bootstrapGroupKey(@Body body: GroupKeyRequest): ApiEnvelope<JsonElement>

  /** The group's members and whether each holds the group key. */
  @GET("auth/member-keys")
  suspend fun members(@Query("chapter") chapter: Int): ApiEnvelope<MemberKeysDto>

  /** A key holder hands the group key to another member, sealed to that member's public key. */
  @PUT("auth/member-key")
  suspend fun handKey(@Body body: MemberKeyRequest): ApiEnvelope<JsonElement>

  /** Stops handing the key out. It cannot take back a key already opened; that needs a rotation. */
  @HTTP(method = "DELETE", path = "auth/member-key", hasBody = true)
  suspend fun takeKey(@Body body: MemberRef): ApiEnvelope<JsonElement>

  /**
   * End-to-end, API PR #95: letters this group can open whose writer had no key when they were recorded
   * (a reply for someone who had not signed in since the switch) and has one now. `wrappedKey` is the
   * group's own envelope; the member's client opens it, seals the content key to `publicKey`, and posts it.
   */
  @GET("messaging/envelopes/missing")
  suspend fun missingEnvelopes(): ApiEnvelope<List<MissingEnvelopeDto>>

  /** A current reader gives one more permitted reader (a partner relay group) the letter's content key. */
  @POST("messaging/envelope")
  suspend fun addEnvelope(@Body body: AddEnvelopeRequest): ApiEnvelope<JsonElement>
}

/**
 * `reason` (required) and `note` go with `returned` only; on any other move they are a 400. `release` is what
 * prints a held letter on purpose: without it the server answers 409 `LetterHeldError`.
 */
@Serializable data class StatusRequest(val id: Int, val status: String, val reason: String? = null, val note: String? = null, val release: Boolean? = null)
@Serializable data class WriterRef(val writer: Int)
@Serializable
data class AddWriterRequest(
  val name: String,
  val email: String? = null,
  val managerNote: String? = null,
  // End-to-end only: the keypair the group made for the writer, private half sealed to the group key of that version.
  val publicKey: String? = null,
  val orgWrappedPrivateKey: String? = null,
  val orgKeyVersion: Int? = null,
)

@Serializable
data class IssueTokenRequest(
  val writer: Int,
  val tokenHash: String? = null,
  val claimWrappedPrivateKey: String? = null,
  val claimSalt: String? = null,
  val claimKdfParams: me.paxana.abcmailbox.crypto.KdfParams? = null,
)

@Serializable data class GroupKeyRequest(val chapter: Int, val publicKey: String, val wrappedOrgPrivateKey: String)
@Serializable data class MemberKeyRequest(val chapter: Int, val user: Int, val wrappedOrgPrivateKey: String)
@Serializable data class MemberRef(val chapter: Int, val user: Int)
@Serializable
data class MissingEnvelopeDto(val message: Int, val readerType: String = "user", val readerId: Int, val publicKey: String? = null, val wrappedKey: String? = null, val keyVersion: Int? = null)

@Serializable data class AddEnvelopeRequest(val message: Int, val readerType: String, val readerId: Int, val wrappedKey: String, val keyVersion: Int? = null)

@Serializable
data class MemberKeysDto(val chapter: Int? = null, val publicKey: String? = null, val keyVersion: Int? = null, val members: List<MemberDto> = emptyList())

@Serializable
data class MemberDto(val id: Int, val username: String? = null, val name: String? = null, val publicKey: String? = null, val holdsGroupKey: Boolean = false)

@Serializable
data class WriterDto(
  val id: Int,
  val name: String? = null,
  val username: String? = null,
  val email: String? = null,
  val managedBy: Int? = null,
  val managerNote: String? = null,
  /** Set on the group's shared anonymous account, which is not a person and cannot be handed off. */
  val anonymousForChapter: Int? = null,
  val publicKey: String? = null,
  /** End-to-end only: the writer's private key sealed to the group, while the account is unclaimed. */
  val orgWrappedPrivateKey: String? = null,
  val claimToken: ClaimTokenStateDto? = null,
  val createdAt: String? = null,
)

@Serializable data class ClaimTokenStateDto(val expiresAt: String? = null)
@Serializable data class IssuedTokenDto(val writer: Int? = null, val token: String? = null, val expiresAt: String? = null)

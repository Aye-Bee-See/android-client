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

  /** Server mode returns the token once. Regenerating replaces the previous one. */
  @POST("auth/writer/token")
  suspend fun issueToken(@Body body: WriterRef): ApiEnvelope<IssuedTokenDto>

  @HTTP(method = "DELETE", path = "auth/writer/token", hasBody = true)
  suspend fun revokeToken(@Body body: WriterRef): ApiEnvelope<JsonElement>
}

@Serializable data class StatusRequest(val id: Int, val status: String)
@Serializable data class WriterRef(val writer: Int)
@Serializable data class AddWriterRequest(val name: String, val email: String? = null, val managerNote: String? = null)

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
  val claimToken: ClaimTokenStateDto? = null,
  val createdAt: String? = null,
)

@Serializable data class ClaimTokenStateDto(val expiresAt: String? = null)
@Serializable data class IssuedTokenDto(val writer: Int? = null, val token: String? = null, val expiresAt: String? = null)

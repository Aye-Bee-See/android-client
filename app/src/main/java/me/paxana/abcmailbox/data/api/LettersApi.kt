package me.paxana.abcmailbox.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * `/chat` and `/messaging`: threads, letters, attachments. Everything here is
 * scoped by the token: a writer only ever sees their own threads.
 *
 * Two Retrofit details worth knowing. DELETE carries a JSON body (the API's
 * convention), which needs the generic `@HTTP` annotation because `@DELETE`
 * forbids a body. Attachment upload is `multipart/form-data`, which Retrofit
 * builds from `@Part` parameters.
 */
interface LettersApi {

  @GET("chat/chats")
  suspend fun chats(
    @Query("prisoner") prisoner: Int? = null,
    @Query("full") full: Boolean = true,
    @Query("page") page: Int = 1,
    @Query("page_size") pageSize: Int = 20,
  ): ApiEnvelope<List<ChatDto>>

  @GET("chat/chat")
  suspend fun chat(@Query("id") id: Int, @Query("full") full: Boolean = true): ApiEnvelope<ChatDto>

  @GET("chat/chat")
  suspend fun chatByPrisoner(@Query("prisoner") prisoner: Int, @Query("full") full: Boolean = true): ApiEnvelope<ChatDto>

  /**
   * [idempotencyKey]: made up once per letter and repeated on every retry of it (API PR #97). A retry of a
   * letter that did arrive gets that letter back instead of creating a second one for the prisoner.
   */
  @POST("messaging/message")
  suspend fun send(@Body body: SendMessageRequest, @Header("Idempotency-Key") idempotencyKey: String? = null): ApiEnvelope<MessageDto>

  @GET("messaging/message")
  suspend fun message(@Query("id") id: Int, @Query("full") full: Boolean = true): ApiEnvelope<MessageDto>

  @PUT("messaging/message")
  suspend fun update(@Body body: UpdateMessageRequest): ApiEnvelope<JsonElement>

  @HTTP(method = "DELETE", path = "messaging/message", hasBody = true)
  suspend fun delete(@Body body: IdBody): ApiEnvelope<JsonElement>

  @Multipart
  @POST("messaging/attachment")
  suspend fun upload(
    @Part("message") message: RequestBody,
    @Part file: MultipartBody.Part,
    /** End-to-end mode: the nonce the file was encrypted with; omitted in server mode. */
    @Part("nonce") nonce: RequestBody? = null,
    @Header("Idempotency-Key") idempotencyKey: String? = null,
  ): ApiEnvelope<AttachmentDto>

  @GET("messaging/attachments")
  suspend fun attachments(@Query("message") message: Int): ApiEnvelope<List<AttachmentDto>>

  @Streaming
  @GET("messaging/attachment")
  suspend fun download(@Query("id") id: Int): ResponseBody

  @HTTP(method = "DELETE", path = "messaging/attachment", hasBody = true)
  suspend fun deleteAttachment(@Body body: IdBody): ApiEnvelope<JsonElement>

  @GET("messaging/retention")
  suspend fun retention(): ApiEnvelope<RetentionDto>
}

@Serializable data class IdBody(val id: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SendMessageRequest(
  /** Server mode. In end-to-end mode this stays null and the four cipher fields plus `envelopes` are sent. */
  val messageText: String? = null,
  val prisoner: Int,
  /** Required by the API; `@EncodeDefault` because defaults are otherwise omitted from the JSON. */
  @EncodeDefault val sender: String = "user",
  /**
   * Group accounts only: a managed writer to send as (omit for the group's anonymous writer),
   * or, with `sender = "prisoner"`, the writer whose thread a reply belongs to. Ignored for writers.
   */
  val user: Int? = null,
  /** Omitted (not null) when unset, so the server resolves the relay group itself. */
  val relayChapter: Int? = null,
  val relayNote: String? = null,
  /** One of this writer's returned letters to the same prisoner, which this letter replaces. It is routed afresh. */
  val resendOf: Int? = null,
  val ciphertext: String? = null,
  val nonce: String? = null,
  val relayNoteCiphertext: String? = null,
  val relayNoteNonce: String? = null,
  val envelopes: List<EnvelopeDto>? = null,
)

/** A letter's content key sealed to one reader. Group readers name the `keyVersion` it was sealed to. */
@Serializable
data class EnvelopeDto(val readerType: String, val readerId: Int, val wrappedKey: String, val keyVersion: Int? = null)

@Serializable
data class UpdateMessageRequest(
  val id: Int,
  val messageText: String? = null,
  val relayNote: String? = null,
  val relayChapter: Int? = null,
  val keep: Boolean? = null,
  val ciphertext: String? = null,
  val nonce: String? = null,
  val relayNoteCiphertext: String? = null,
  val relayNoteNonce: String? = null,
)

@Serializable
data class ChatDto(
  val id: Int,
  val user: Int? = null,
  val prisoner: Int,
  val createdAt: String? = null,
  val updatedAt: String? = null,
  val lastMessageAt: String? = null,
  @SerialName("last_message") val lastMessage: LastMessageDto? = null,
  val messages: List<MessageDto>? = null,
  /** API PR #117: how many of the thread's letters are held, and the distinct reasons. */
  val heldCount: Int = 0,
  val heldReasons: List<String> = emptyList(),
  @SerialName("user_details") val userDetails: UserDto? = null,
  @SerialName("prisoner_details") val prisonerDetails: PrisonerDto? = null,
)

@Serializable
data class LastMessageDto(
  val id: Int,
  val sender: String,
  val messageText: String? = null,
  val status: String? = null,
  val createdAt: String? = null,
  val ciphertext: String? = null,
  val nonce: String? = null,
  val envelopes: List<EnvelopeDto>? = null,
)

@Serializable
data class MessageDto(
  val id: Int,
  val chat: Int? = null,
  val sender: String,
  val prisoner: Int,
  val user: Int? = null,
  val status: String? = null,
  val relayChapter: Int? = null,
  val relayNote: String? = null,
  val messageText: String? = null,
  val keep: Boolean = false,
  val statusChangedAt: String? = null,
  val statusChangedBy: Int? = null,
  val createdAt: String? = null,
  val updatedAt: String? = null,
  @SerialName("status_history") val statusHistory: List<StatusHistoryDto>? = null,
  val attachments: List<AttachmentDto>? = null,
  @SerialName("relay_group") val relayGroup: RelayGroupDto? = null,
  // Returned mail and held letters (API PRs #105, #106). All read-only except `resendOf`, which is set on create.
  val returnReason: String? = null,
  /**
   * API PR #117: what the envelope said, on the letter itself. "Absent" (an older API, which keeps it on the history
   * only) and "null" (not returned) have to be told apart, and a JSON null on a nullable field decodes to Kotlin null,
   * so the default is a marker that no real answer contains.
   */
  @Serializable(with = ReturnNoteField::class) val returnNote: String = ReturnNoteField.ABSENT,
  val heldReason: String? = null,
  val resendOf: Int? = null,
  @SerialName("resent_as") val resentAs: List<ResentDto>? = null,
  /**
   * With `full=true`, on lists and on the single read (API PR #111): who the letter is for, with the facility's name,
   * address, routing, limits and mail rules. Everything an envelope and a print run need, so a queue of a hundred
   * letters is one request and not a hundred and one. Absent from an older API.
   */
  @SerialName("prisoner_details") val prisonerDetails: PrisonerDto? = null,
  // End-to-end mode: `messageText` is null and these carry the letter; `envelopes` is filtered to the caller.
  val ciphertext: String? = null,
  val nonce: String? = null,
  val relayNoteCiphertext: String? = null,
  val relayNoteNonce: String? = null,
  val envelopes: List<EnvelopeDto>? = null,
)

/**
 * Three answers for one field: absent (an older API), `null` (not returned), a note. `coerceInputValues` turns a JSON
 * null on a non-null field into its default before any serializer sees it, so this one declares a nullable shape and
 * reads the null itself, into a marker no real note contains.
 */
object ReturnNoteField : kotlinx.serialization.KSerializer<String> {
  const val ABSENT = "\u0000absent"
  const val NONE = "\u0000none"
  override val descriptor = String.serializer().nullable.descriptor
  override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String = if (decoder.decodeNotNullMark()) decoder.decodeString() else { decoder.decodeNull(); NONE }
  override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) = if (value == NONE || value == ABSENT) encoder.encodeNull() else encoder.encodeString(value)
}

@Serializable data class RelayGroupDto(val id: Int, val name: String)
@Serializable data class ResentDto(val id: Int, val status: String? = null, val createdAt: String? = null)

@Serializable
data class StatusHistoryDto(
  val id: Int,
  val fromStatus: String? = null,
  val toStatus: String,
  val changedBy: Int? = null,
  val createdAt: String? = null,
  /** With a move to `returned` only: why it came back, and what the envelope said. */
  val reason: String? = null,
  val note: String? = null,
)

@Serializable
data class AttachmentDto(
  val id: Int,
  val message: Int,
  val originalName: String,
  val mimeType: String,
  val size: Long,
  val uploadedBy: Int? = null,
  val createdAt: String? = null,
  val nonce: String? = null,
)

@Serializable
data class RetentionDto(
  val defaultDays: Int? = null,
  val maxDays: Int? = null,
  val chosenDays: Int? = null,
  val effectiveDays: Int? = null,
  val coversReplies: Boolean = true,
)

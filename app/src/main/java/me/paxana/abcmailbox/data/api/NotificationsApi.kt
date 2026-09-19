package me.paxana.abcmailbox.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * The account's notification feed and this phone's registration for pushes (API PR #96).
 *
 * A push from this API is a doorbell and nothing else: its whole payload is `{"type":"sync"}`. What
 * happened is in the feed, which the app fetches over its own connection and words on the phone, in
 * the user's language. Feed entries hold ids and states, never names or letter text. The feed works
 * without push; push only makes it prompt.
 */
interface NotificationsApi {
  /** Newest first. [since] is the id of the newest entry this phone already has. */
  @GET("auth/notifications")
  suspend fun feed(@Query("since") since: Int? = null, @Query("unread") unread: Boolean? = null, @Query("page_size") pageSize: Int = 50): ApiEnvelope<List<NotificationDto>>

  /** `{}` marks everything read; `upTo` everything up to an id. */
  @PUT("auth/notifications/read")
  suspend fun markRead(@Body body: MarkReadRequest = MarkReadRequest()): ApiEnvelope<JsonElement>

  /**
   * Call after every sign-in, after a password change, and whenever the push service issues a new token;
   * the same token again only refreshes it. The server ties the device to the session that registered it,
   * so signing out stops its pushes without a call from here.
   */
  @POST("auth/device")
  suspend fun registerDevice(@Body body: RegisterDeviceRequest): ApiEnvelope<DeviceRegistrationDto>

  @HTTP(method = "DELETE", path = "auth/device", hasBody = true)
  suspend fun removeDevice(@Body body: IdBody): ApiEnvelope<JsonElement>
}

@Serializable
data class NotificationDto(
  val id: Int,
  val event: String,
  val chat: Int? = null,
  val message: Int? = null,
  val submission: Int? = null,
  val detail: JsonObject? = null,
  val readAt: String? = null,
  val createdAt: String? = null,
)

@Serializable data class MarkReadRequest(val upTo: Int? = null, val ids: List<Int>? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RegisterDeviceRequest(
  val token: String,
  // Required by the API. kotlinx.serialization leaves out a field that still has its default value unless told otherwise.
  @EncodeDefault val platform: String = "android",
  val label: String? = null,
)

/** The registered device, flat (checked against the live API: `created` and `deliverable` sit beside the device's own fields). */
@Serializable
data class DeviceRegistrationDto(
  val id: Int,
  val label: String? = null,
  val muted: Boolean = false,
  val created: Boolean = false,
  /** Whether the server can actually send through that provider today. It runs without push until it is configured. */
  val deliverable: Boolean = false,
)

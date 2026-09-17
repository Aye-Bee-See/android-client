package me.paxana.abcmailbox.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * `/auth`: accounts and sessions. Retrofit turns each function into an HTTP
 * call; `suspend` makes the call run on OkHttp's threads and resume the caller.
 * Remember the API's convention: ids and selectors go in the query string on
 * GET, and in the JSON body on PUT and DELETE.
 */
interface AuthApi {

  @POST("auth/login")
  suspend fun login(@Body body: LoginRequest): ApiEnvelope<LoginData>

  /** Ends this token, or every token for the account with `everywhere = true`. */
  @POST("auth/logout")
  suspend fun logout(@Body body: LogoutRequest = LogoutRequest()): ApiEnvelope<JsonElement>

  @GET("auth/user")
  suspend fun user(@Query("id") id: Int): ApiEnvelope<UserDto>

  /** Public: who a claim token is for. 404 unknown or revoked, 410 used or expired, 429 when checked too often. */
  @GET("auth/claim")
  suspend fun claimInfo(@Query("token") token: String): ApiEnvelope<ClaimInfoDto>

  /** Public: turn a managed account into an independent one. 201, then sign in. */
  @POST("auth/claim")
  suspend fun claim(@Body body: ClaimRequest): ApiEnvelope<JsonElement>

  /** A self password change ends every other session and returns a fresh `token` to keep this one alive. */
  @PUT("auth/user")
  suspend fun updateUser(@Body body: UpdateUserRequest): ApiEnvelope<UpdateUserData>
}

@Serializable
data class ClaimInfoDto(val writer: NamedRef, val chapter: NamedRef? = null, val expiresAt: String? = null)

@Serializable
data class NamedRef(val id: Int, val name: String? = null)

@Serializable
data class ClaimRequest(val token: String, val username: String, val password: String, val email: String? = null)

@Serializable
data class UpdateUserRequest(
  val id: Int,
  val password: String? = null,
  val name: String? = null,
  val email: String? = null,
  val bio: String? = null,
)

@Serializable
data class UpdateUserData(val token: TokenDto? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LogoutRequest(val everywhere: Boolean = false)

@Serializable
data class LoginData(
  val user: UserDto,
  val token: TokenDto,
  /** Present in end-to-end mode only; opaque until phase 5. */
  val keys: JsonElement? = null,
)

@Serializable
data class TokenDto(val token: String, val expires: Long)

/** The user record as the API returns it. Wrapped keys and passwords never appear. */
@Serializable
data class UserDto(
  val id: Int,
  val username: String,
  val email: String? = null,
  val name: String? = null,
  val bio: String? = null,
  val role: String,
  val chapterId: Int? = null,
  val managedBy: Int? = null,
  val claimedAt: String? = null,
  val anonymousForChapter: Int? = null,
  val publicKey: String? = null,
  val createdAt: String? = null,
  val updatedAt: String? = null,
)

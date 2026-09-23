package me.paxana.abcmailbox.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.paxana.abcmailbox.crypto.KdfParams
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
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

  /**
   * Step one of signing in (API PR #114): which scheme the account uses and, for the split scheme, the salt and
   * recipe the device derives its keys with. Public. For an unknown name the answer is a made-up but stable salt,
   * so it never says whether an account exists. Absent from an older API (404): then everything is `plain`.
   */
  @GET("auth/login-params")
  suspend fun loginParams(@Query("username") username: String): ApiEnvelope<LoginParamsDto>

  @POST("auth/login")
  suspend fun login(@Body body: LoginRequest): ApiEnvelope<LoginData>

  /** Ends this token, or every token for the account with `everywhere = true`. */
  @POST("auth/logout")
  suspend fun logout(@Body body: LogoutRequest = LogoutRequest()): ApiEnvelope<JsonElement>

  /**
   * Deletes an account and everything the person wrote or received through it (API PR #104). One's own account
   * needs the password again: 403 when it is wrong (nothing deleted), 429 after too many guesses, and
   * 409 `AccountDeleteError` when the account may not go (the only admin, the last holder of a group's key).
   * DELETE with a body, like every delete in this API, which Retrofit only allows through `@HTTP`.
   */
  @HTTP(method = "DELETE", path = "auth/user", hasBody = true)
  suspend fun deleteUser(@Body body: DeleteAccountRequest): ApiEnvelope<DeletionReportDto>

  @GET("auth/user")
  suspend fun user(@Query("id") id: Int): ApiEnvelope<UserDto>

  /** Public: who a claim token is for. 404 unknown or revoked, 410 used or expired, 429 when checked too often. */
  @GET("auth/claim")
  suspend fun claimInfo(@Query("token") token: String): ApiEnvelope<ClaimInfoDto>

  /** Public: turn a managed account into an independent one. 201, then sign in. */
  @POST("auth/claim")
  suspend fun claim(@Body body: ClaimRequest): ApiEnvelope<JsonElement>

  /** The caller's key bundle: wrapped private key, salt, KDF parameters, and the group key when a member. */
  @GET("auth/keys")
  suspend fun keys(): ApiEnvelope<KeyBundleDto>

  /** Sets the public key (once) and the wrapped private key; also used to rotate the recovery code. */
  @PUT("auth/keys")
  suspend fun putKeys(@Body body: KeyFieldsRequest): ApiEnvelope<JsonElement>

  /** A user's or group's public key, to seal an envelope to. Groups also report their `keyVersion`. */
  @GET("auth/public-key")
  suspend fun publicKey(@Query("user") user: Int? = null, @Query("chapter") chapter: Int? = null): ApiEnvelope<PublicKeyDto>

  /** Public: the recovery-wrapped key and a challenge sealed to the account's public key (ten minutes, single use). */
  @GET("auth/recover")
  suspend fun recoverStart(@Query("username") username: String): ApiEnvelope<RecoverStartDto>

  @POST("auth/recover")
  suspend fun recoverFinish(@Body body: RecoverFinishRequest): ApiEnvelope<JsonElement>

  /** A self password change ends every other session and returns a fresh `token` to keep this one alive. */
  @PUT("auth/user")
  suspend fun updateUser(@Body body: UpdateUserRequest): ApiEnvelope<UpdateUserData>
}

@Serializable
data class ClaimInfoDto(
  val writer: NamedRef,
  val chapter: NamedRef? = null,
  val expiresAt: String? = null,
  // End-to-end mode: the writer's keypair, private half wrapped under the claim token.
  val publicKey: String? = null,
  val claimWrappedPrivateKey: String? = null,
  val claimSalt: String? = null,
  val claimKdfParams: JsonElement? = null,
) {
  val hasKeyMaterial: Boolean get() = publicKey != null && claimWrappedPrivateKey != null && claimSalt != null && claimKdfParams != null
}

@Serializable
data class NamedRef(val id: Int, val name: String? = null)

@Serializable
data class ClaimRequest(
  val token: String,
  val username: String,
  val password: String,
  val email: String? = null,
  // End-to-end mode: the same private key, re-wrapped under the new password and a new recovery code.
  val wrappedPrivateKey: String? = null,
  val kdfSalt: String? = null,
  val kdfParams: KdfParams? = null,
  val recoveryWrappedPrivateKey: String? = null,
  val recoverySalt: String? = null,
  val recoveryKdfParams: KdfParams? = null,
  /** `"split"`: `password` is the auth key, derived with `kdfSalt`/`kdfParams` (API PR #114). Absent means plain. */
  val authScheme: String? = null,
)

@Serializable
data class UpdateUserRequest(
  val id: Int,
  val password: String? = null,
  val name: String? = null,
  val email: String? = null,
  val bio: String? = null,
  // End-to-end mode: a password change must carry the private key re-wrapped under the new password.
  val wrappedPrivateKey: String? = null,
  val kdfSalt: String? = null,
  val kdfParams: KdfParams? = null,
  // End-to-end mode, managing group only, once: the keypair it made for an unclaimed writer who has none.
  val publicKey: String? = null,
  val orgWrappedPrivateKey: String? = null,
  val orgKeyVersion: Int? = null,
  /** `"split"`: `password` is the auth key, derived with `kdfSalt`/`kdfParams` (API PR #114). Absent means plain. */
  val authScheme: String? = null,
)

@Serializable
data class UpdateUserData(val token: TokenDto? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginParamsDto(val scheme: String? = null, val kdfSalt: String? = null, val kdfParams: JsonElement? = null) {
  val isSplit: Boolean get() = scheme == "split" && kdfSalt != null && kdfParams != null
  val isPlain: Boolean get() = scheme == "plain"
  /** Anything else ("split" without its salt, a scheme this app does not know) must not be mistaken for plain: the password would go out. */
  val isWellFormed: Boolean get() = isSplit || isPlain
}

@Serializable
data class DeleteAccountRequest(val id: Int, val password: String? = null)

/** What went with the account. Defaults, so an API that stops counting something does not break leaving. */
@Serializable
data class DeletionReportDto(val deleted: Int = 0, val letters: Int = 0, val replies: Int = 0, val attachments: Int = 0, val threads: Int = 0)

@Serializable
data class LogoutRequest(val everywhere: Boolean = false)

@Serializable
data class LoginData(
  val user: UserDto,
  val token: TokenDto,
  /** End-to-end mode only. An account with no keys yet gets an object of nulls. */
  val keys: KeyBundleDto? = null,
)

@Serializable
data class KeyBundleDto(
  val publicKey: String? = null,
  val wrappedPrivateKey: String? = null,
  val kdfSalt: String? = null,
  /** Kept as raw JSON: it is parsed (and validated) only when a key is actually unwrapped. */
  val kdfParams: JsonElement? = null,
  val hasRecovery: Boolean = false,
  val orgKey: OrgKeyDto? = null,
) {
  val hasKeys: Boolean get() = publicKey != null && wrappedPrivateKey != null && kdfSalt != null && kdfParams != null
}

@Serializable
data class OrgKeyDto(
  val chapterId: Int,
  val chapterName: String? = null,
  val chapterPublicKey: String? = null,
  val wrappedOrgPrivateKey: String? = null,
  val keyVersion: Int? = null,
)

@Serializable
data class KeyFieldsRequest(
  val publicKey: String? = null,
  val wrappedPrivateKey: String? = null,
  val kdfSalt: String? = null,
  val kdfParams: KdfParams? = null,
  val recoveryWrappedPrivateKey: String? = null,
  val recoverySalt: String? = null,
  val recoveryKdfParams: KdfParams? = null,
)

@Serializable
data class PublicKeyDto(val publicKey: String? = null, val keyVersion: Int? = null)

@Serializable
data class RecoverStartDto(
  val publicKey: String,
  val recoveryWrappedPrivateKey: String,
  val recoverySalt: String,
  val recoveryKdfParams: JsonElement,
  val sealedChallenge: String,
)

@Serializable
data class RecoverFinishRequest(
  val username: String,
  val challenge: String,
  val password: String,
  val wrappedPrivateKey: String,
  val kdfSalt: String,
  val kdfParams: KdfParams,
  val recoveryWrappedPrivateKey: String? = null,
  val recoverySalt: String? = null,
  val recoveryKdfParams: KdfParams? = null,
  /** `"split"`: `password` is the auth key, derived with `kdfSalt`/`kdfParams` (API PR #114). Absent means plain. */
  val authScheme: String? = null,
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

package me.paxana.abcmailbox.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** `GET /health`: liveness plus the encryption mode the server speaks (API PR #80). */
interface HealthApi {
  @GET("health")
  suspend fun health(): HealthDto
}

@Serializable
data class HealthDto(val status: String? = null, val encryptionMode: String? = null)

package me.paxana.abcmailbox.data.api

import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Marks a request that must go out without the session token, whoever is signed in.
 * It travels as an OkHttp tag: an object attached to the request in memory, never sent.
 */
object Anonymous

/**
 * The downloads behind the offline copy of the directory. Every call is [Anonymous], so
 * the copy holds exactly what the public sees: a group member's or admin's view includes
 * unpublished records and verification notes, and those must not land in a file that
 * outlives their session.
 *
 * Rows come back as raw [JsonObject]s and are stored as they are. A `full=true` list row
 * has the same shape as the single read, so a detail page can be rebuilt offline with the
 * same DTOs and mappers the online path uses.
 */
interface DirectorySyncApi {
  @GET("prisoner/prisoners")
  suspend fun prisoners(@Query("page") page: Int, @Query("page_size") pageSize: Int = PAGE, @Query("full") full: Boolean = true, @Tag anonymous: Anonymous = Anonymous): ApiEnvelope<List<JsonObject>>

  @GET("prison/prisons")
  suspend fun prisons(@Query("page") page: Int, @Query("page_size") pageSize: Int = PAGE, @Query("full") full: Boolean = true, @Tag anonymous: Anonymous = Anonymous): ApiEnvelope<List<JsonObject>>

  @GET("chapter/chapters")
  suspend fun chapters(@Query("page") page: Int, @Query("page_size") pageSize: Int = PAGE, @Query("full") full: Boolean = true, @Tag anonymous: Anonymous = Anonymous): ApiEnvelope<List<JsonObject>>

  @GET("prison/mail-rules")
  suspend fun mailRules(@Tag anonymous: Anonymous = Anonymous): ApiEnvelope<JsonObject>

  companion object { const val PAGE = 100 } // the API's maximum page size
}

package me.paxana.abcmailbox.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The public directory: prisons, prisoners, rules, chapters. Every call here
 * works without a token; signed-in staff simply see more records. A `null`
 * query parameter is omitted from the URL, which is how optional filters work.
 */
interface DirectoryApi {

  @GET("prisoner/prisoners")
  suspend fun prisoners(
    @Query("q") q: String? = null,
    @Query("prison") prison: Int? = null,
    @Query("status") status: String? = null,
    @Query("country") country: String? = null,
    @Query("featured") featured: Boolean? = null,
    @Query("sort") sort: String? = null,
    @Query("page") page: Int = 1,
    @Query("page_size") pageSize: Int = 20,
  ): ApiEnvelope<List<PrisonerDto>>

  @GET("prisoner/prisoner")
  suspend fun prisoner(@Query("id") id: Int, @Query("full") full: Boolean = true): ApiEnvelope<PrisonerDto>

  @GET("prison/prisons")
  suspend fun prisons(
    @Query("q") q: String? = null,
    @Query("country") country: String? = null,
    @Query("routing") routing: String? = null,
    @Query("relay") relay: Boolean? = null,
    @Query("sort") sort: String? = null,
    @Query("page") page: Int = 1,
    @Query("page_size") pageSize: Int = 20,
  ): ApiEnvelope<List<PrisonDto>>

  /** Public, and only changes with an API release: the tag vocabulary with default English labels. */
  @GET("prison/mail-rules")
  suspend fun mailRuleVocabulary(): ApiEnvelope<MailRuleVocabularyDto>

  @GET("prison/prison")
  suspend fun prison(@Query("id") id: Int, @Query("full") full: Boolean = true): ApiEnvelope<PrisonDto>

  @GET("chapter/chapters")
  suspend fun chapters(
    @Query("q") q: String? = null,
    @Query("country") country: String? = null,
    @Query("service") service: String? = null,
    @Query("networkRole") networkRole: String? = null,
    @Query("sort") sort: String? = null,
    @Query("page") page: Int = 1,
    @Query("page_size") pageSize: Int = 20,
  ): ApiEnvelope<List<ChapterDto>>

  @GET("chapter/chapter")
  suspend fun chapter(@Query("id") id: Int, @Query("full") full: Boolean = true): ApiEnvelope<ChapterDto>
}

@Serializable
data class PrisonerDto(
  val id: Int,
  val birthName: String? = null,
  val chosenName: String? = null,
  val aliases: List<String>? = null,
  val prison: Int? = null,
  val country: String? = null,
  val inmateID: String? = null,
  val releaseDate: String? = null,
  val detainedSince: String? = null,
  val sentence: String? = null,
  val charges: String? = null,
  val estimatedRelease: String? = null,
  val bio: String? = null,
  val interests: List<String>? = null,
  val photoUrl: String? = null,
  val supportWebsite: String? = null,
  val donationInfo: String? = null,
  val status: String? = null,
  val statusNotice: String? = null,
  val featured: Boolean = false,
  val verifiedBy: Int? = null,
  val verifiedAt: String? = null,
  val recordStatus: String? = null,
  @SerialName("prison_details") val prisonDetails: PrisonDto? = null,
  @SerialName("support_groups") val supportGroups: List<ChapterDto>? = null,
)

@Serializable
data class PrisonDto(
  val id: Int,
  val prisonName: String,
  val address: JsonObject? = null,
  val country: String? = null,
  val routing: String? = null,
  val scanService: String? = null,
  val notes: String? = null,
  val verifiedBy: Int? = null,
  val verifiedAt: String? = null,
  val recordStatus: String? = null,
  /** Tags from the master list of mail rules (API PRs #86, #93); the three valued rules sit beside them. */
  val mailRules: List<String>? = null,
  /**
   * The same rules with their wording (API PR #93). The master list can change while the app runs,
   * since admins add and retire rules, so this is the authority for how a facility's rules read.
   */
  @SerialName("mail_rule_details") val mailRuleDetails: List<MailRuleDto>? = null,
  val pageLimit: Int? = null,
  val photoLimit: Int? = null,
  val mailLanguages: List<String>? = null,
  val prisoners: List<PrisonerDto>? = null,
  @SerialName("relay_groups") val relayGroups: List<ChapterDto>? = null,
)

@Serializable
data class MailRuleVocabularyDto(val categories: List<String> = emptyList(), val rules: List<MailRuleDto> = emptyList())

@Serializable
data class MailRuleDto(val tag: String, val category: String = "other", val label: String? = null, val description: String? = null)

@Serializable
data class ChapterDto(
  val id: Int,
  val name: String,
  val location: JsonObject? = null,
  val subregion: String? = null,
  val country: String? = null,
  val about: String? = null,
  val website: String? = null,
  val email: String? = null,
  val socialLinks: Map<String, String?>? = null,
  val services: List<String>? = null,
  val announcement: String? = null,
  val networkRole: String? = null,
  val accountStatus: String? = null,
  val vouchedBy: Int? = null,
  val recordStatus: String? = null,
  @SerialName("supported_prisoners") val supportedPrisoners: List<PrisonerDto>? = null,
  @SerialName("relay_prisons") val relayPrisons: List<PrisonDto>? = null,
  /** Present when the chapter is embedded on a prisoner as a support group. */
  @SerialName("PrisonerSupport") val prisonerSupport: PrisonerSupportDto? = null,
)

@Serializable
data class PrisonerSupportDto(val description: String? = null)

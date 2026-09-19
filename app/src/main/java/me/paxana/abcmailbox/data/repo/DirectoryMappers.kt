package me.paxana.abcmailbox.data.repo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.paxana.abcmailbox.data.api.ChapterDto
import me.paxana.abcmailbox.data.api.PrisonDto
import me.paxana.abcmailbox.data.api.PrisonerDto
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.MailRule
import me.paxana.abcmailbox.domain.MailRuleCatalog
import me.paxana.abcmailbox.domain.MailRules
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.Routing
import me.paxana.abcmailbox.domain.Verification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** ISO-8601 instants from the API; anything unparseable becomes null rather than a crash. */
internal fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
internal fun String?.toLocalDateOrNull(): LocalDate? = toInstantOrNull()?.atZone(ZoneOffset.UTC)?.toLocalDate()

/**
 * `address` and `location` are free-form JSON objects. The seeds use
 * `{"street": ...}`; real records may add city, region, postcode. Known keys
 * come out in postal order, unknown ones after, empty values dropped.
 */
internal fun JsonObject?.toAddressLines(): List<String> {
  if (this == null) return emptyList()
  val order = listOf("name", "line1", "line2", "street", "city", "region", "state", "postcode", "zip", "country")
  val known = order.mapNotNull { key -> this[key]?.asText()?.let { key to it } }
  val rest = this.filterKeys { it !in order }.mapNotNull { (k, v) -> v.asText()?.let { k to it } }
  return (known + rest).map { it.second }.filter { it.isNotBlank() }
}

private fun kotlinx.serialization.json.JsonElement.asText(): String? =
  (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

/** `catalog` supplies labels for the rule tags; the default is the compiled-in vocabulary. */
fun PrisonDto.toDomain(catalog: MailRuleCatalog = MailRuleCatalog.Compiled): Facility = Facility(
  id = id,
  name = prisonName,
  addressLines = address.toAddressLines(),
  country = country,
  routing = Routing.fromKey(routing),
  scanService = scanService?.takeIf { it.isNotBlank() },
  notes = notes?.takeIf { it.isNotBlank() },
  verification = Verification(verifiedBy, verifiedAt.toInstantOrNull()),
  prisoners = prisoners.orEmpty().map { it.toDomain(catalog) },
  rules = MailRules(
    rules = catalog.resolveAll(
      mailRules.orEmpty(),
      // A detail with no label of its own falls back to what the catalog knows for that tag.
      mailRuleDetails.orEmpty().map { d -> MailRule(d.tag, d.category, d.label?.takeIf { it.isNotBlank() } ?: catalog.resolve(d.tag).label, d.description) },
    ),
    pageLimit = pageLimit?.takeIf { it > 0 },
    photoLimit = photoLimit?.takeIf { it > 0 },
    languages = mailLanguages.orEmpty().map { it.lowercase() },
  ),
  relayGroups = relayGroups.orEmpty().map { it.toDomain(catalog) },
)

fun PrisonerDto.toDomain(catalog: MailRuleCatalog = MailRuleCatalog.Compiled): Prisoner = Prisoner(
  id = id,
  name = chosenName?.takeIf { it.isNotBlank() } ?: birthName?.takeIf { it.isNotBlank() } ?: "Unnamed",
  birthName = birthName?.takeIf { it.isNotBlank() && it != chosenName },
  aliases = aliases.orEmpty().filter { it.isNotBlank() },
  facilityId = prison,
  facility = prisonDetails?.toDomain(catalog),
  country = country ?: prisonDetails?.country,
  detainedSince = detainedSince.toLocalDateOrNull(),
  releaseDate = releaseDate.toLocalDateOrNull(),
  sentence = sentence?.takeIf { it.isNotBlank() },
  charges = charges?.takeIf { it.isNotBlank() },
  estimatedRelease = estimatedRelease?.takeIf { it.isNotBlank() },
  bio = bio?.takeIf { it.isNotBlank() },
  interests = interests.orEmpty().filter { it.isNotBlank() },
  photoUrl = photoUrl?.takeIf { it.isNotBlank() },
  supportWebsite = supportWebsite?.takeIf { it.isNotBlank() },
  donationInfo = donationInfo?.takeIf { it.isNotBlank() },
  status = status,
  statusNotice = statusNotice?.takeIf { it.isNotBlank() },
  featured = featured,
  verification = Verification(verifiedBy, verifiedAt.toInstantOrNull()),
  supportGroups = supportGroups.orEmpty().map { it.toDomain(catalog) },
  inmateId = inmateID?.takeIf { it.isNotBlank() },
)

fun ChapterDto.toDomain(catalog: MailRuleCatalog = MailRuleCatalog.Compiled): Group = Group(
  id = id,
  name = name,
  subregion = subregion?.takeIf { it.isNotBlank() } ?: location.toAddressLines().firstOrNull(),
  country = country?.takeIf { it.isNotBlank() },
  about = about?.takeIf { it.isNotBlank() },
  website = website?.takeIf { it.isNotBlank() },
  email = email?.takeIf { it.isNotBlank() },
  socialLinks = socialLinks.orEmpty().mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap(),
  services = services.orEmpty(),
  announcement = announcement?.takeIf { it.isNotBlank() },
  networkRole = networkRole,
  accountStatus = accountStatus,
  supportedPrisoners = supportedPrisoners.orEmpty().map { it.toDomain(catalog) },
  relayPrisons = relayPrisons.orEmpty().map { it.toDomain(catalog) },
  supportDescription = prisonerSupport?.description?.takeIf { it.isNotBlank() },
)

package me.paxana.abcmailbox.domain

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

/**
 * What the screens work with. These carry the facts the UI needs already
 * derived (display names, address lines, staleness) so that the API's shape
 * is absorbed once, in the mappers, and never in a composable.
 */

data class Verification(val byGroupId: Int?, val at: Instant?) {
  /** Six months, matching the web site's "6+ months unverified" warning and the API's `stale` filter. */
  fun isStale(now: Instant = Instant.now()): Boolean =
    at == null || Period.between(at.atZone(ZoneOffset.UTC).toLocalDate(), now.atZone(ZoneOffset.UTC).toLocalDate()).toTotalMonths() >= 6
}

enum class Routing(val key: String, val label: String, val explanation: String) {
  DIRECT("direct", "Direct mail", "Letters are mailed straight to the facility."),
  SCAN_ONLY("scan_only", "Scan service only", "Physical mail is not accepted; letters go through a scanning service."),
  DIRECT_AND_SCAN("direct_and_scan", "Direct mail or scan service", "Letters can be mailed or sent through a scanning service."),
  RELAY_ONLY("relay_only", "Relay only", "Direct mail is not accepted. A relay group must mail the letter locally."),
  UNKNOWN("", "Routing unknown", "Check with a support group before writing.");

  companion object {
    fun fromKey(key: String?): Routing = entries.firstOrNull { it.key == key && key.isNotEmpty() } ?: UNKNOWN
  }
}

data class Facility(
  val id: Int,
  val name: String,
  val addressLines: List<String>,
  val country: String?,
  val routing: Routing,
  val scanService: String?,
  val notes: String?,
  val verification: Verification,
  val prisoners: List<Prisoner>,
  val rules: MailRules,
  val relayGroups: List<Group>,
) {
  val shortLocation: String get() = listOfNotNull(addressLines.lastOrNull(), country).joinToString(", ")
}

data class Prisoner(
  val id: Int,
  val name: String,
  val birthName: String?,
  val aliases: List<String>,
  val facilityId: Int?,
  val facility: Facility?,
  val country: String?,
  val detainedSince: LocalDate?,
  val releaseDate: LocalDate?,
  val sentence: String?,
  val charges: String?,
  val estimatedRelease: String?,
  val bio: String?,
  val interests: List<String>,
  val photoUrl: String?,
  val supportWebsite: String?,
  val donationInfo: String?,
  val status: String?,
  val statusNotice: String?,
  val featured: Boolean,
  val verification: Verification,
  val supportGroups: List<Group>,
  /** The facility-issued number; most facilities refuse mail without it on the envelope. */
  val inmateId: String? = null,
) {
  /** "Est. release" as the site shows it: the free-text estimate wins, then the date's year. */
  val releaseSummary: String get() = estimatedRelease?.takeIf { it.isNotBlank() } ?: releaseDate?.year?.toString() ?: "Unknown"
}

data class Group(
  val id: Int,
  val name: String,
  val subregion: String?,
  val country: String?,
  val about: String?,
  val website: String?,
  val email: String?,
  val socialLinks: Map<String, String>,
  val services: List<String>,
  val announcement: String?,
  val networkRole: String?,
  val accountStatus: String?,
  val supportedPrisoners: List<Prisoner>,
  val relayPrisons: List<Facility>,
  /** How this group supports a particular prisoner, when embedded on that prisoner. */
  val supportDescription: String?,
) {
  val location: String get() = listOfNotNull(subregion, country).joinToString(", ")
}

/** Service keys the API accepts, with the labels the site uses. */
object Services {
  val labels: Map<String, String> = linkedMapOf(
    "letter_collection" to "Letter collection",
    "letter_writing_nights" to "Letter writing nights",
    "domestic_mailing" to "Domestic mailing",
    "international_mailing" to "International mailing",
    "international_relay" to "International relay",
    "translation_assistance" to "Translation assistance",
    "legal_support_coordination" to "Legal support coordination",
    "book_programs" to "Book programs",
  )

  fun label(key: String): String = labels[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

object NetworkRoles {
  fun label(key: String?): String = when (key) {
    "collecting" -> "Collecting group: gathers letters and forwards them to relay partners"
    "relay" -> "Relay group: prints and mails letters locally"
    "both" -> "Collects letters and relays mail"
    else -> "Role not set"
  }
}

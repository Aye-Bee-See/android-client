package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import androidx.annotation.StringRes
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

enum class Routing(val key: String, @StringRes val labelRes: Int, @StringRes val explanationRes: Int) {
  DIRECT("direct", R.string.routing_direct, R.string.routing_direct_explained),
  SCAN_ONLY("scan_only", R.string.routing_scan_only, R.string.routing_scan_only_explained),
  DIRECT_AND_SCAN("direct_and_scan", R.string.routing_direct_and_scan, R.string.routing_direct_and_scan_explained),
  RELAY_ONLY("relay_only", R.string.routing_relay_only, R.string.routing_relay_only_explained),
  UNKNOWN("", R.string.routing_unknown, R.string.routing_unknown_explained);

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
  val releaseSummary: String? get() = estimatedRelease?.takeIf { it.isNotBlank() } ?: releaseDate?.year?.toString()
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

/** Service keys the API accepts. Their names are resources called `service_<key>`, so a key the app has never seen still reads as words. */
object Services {
  val keys: List<String> = listOf(
    "letter_collection", "letter_writing_nights", "domestic_mailing", "international_mailing",
    "international_relay", "translation_assistance", "legal_support_coordination", "book_programs",
  )

  fun label(key: String, strings: Strings): String = strings.byName("service_$key") ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

object NetworkRoles {
  @StringRes fun labelRes(key: String?): Int = when (key) {
    "collecting" -> R.string.role_collecting
    "relay" -> R.string.role_relay
    "both" -> R.string.role_both
    else -> R.string.role_not_set
  }
}


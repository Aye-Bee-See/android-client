package me.paxana.abcmailbox.domain

import java.util.Locale

/** One tag from the API's mail-rule vocabulary, with its default English wording. */
data class MailRule(val tag: String, val category: String, val label: String, val description: String?)

/**
 * The vocabulary the app knows at this moment: the live one when it has been
 * fetched, otherwise the compiled-in copy. A tag that neither knows still
 * displays (as readable text made from the tag), and never breaks anything:
 * the API's contract is that clients ignore what they do not understand.
 */
class MailRuleCatalog(private val categories: List<String>, rules: List<MailRule>) {
  private val byTag = rules.associateBy { it.tag }

  fun resolve(tag: String): MailRule = byTag[tag]
    ?: MailRule(tag, "other", tag.replace('_', ' ').replaceFirstChar { it.uppercase() }, null)

  /**
   * Rules in the vocabulary's display order, unknown tags last. [details] is what the facility itself
   * says about its rules, and wins: admins add and retire rules while the app is running, so a tag can
   * be newer than the list this catalog was built from, or retired and no longer on it at all.
   */
  fun resolveAll(tags: List<String>, details: List<MailRule> = emptyList()): List<MailRule> {
    val fromFacility = details.associateBy { it.tag }
    return tags.distinct().map { fromFacility[it] ?: resolve(it) }
      .sortedBy { r -> categories.indexOf(r.category).let { if (it < 0) Int.MAX_VALUE else it } }
  }

  companion object {
    val Compiled = MailRuleCatalog(CompiledMailRules.categories, CompiledMailRules.rules)
  }
}

/** A facility's mail rules: tags plus the three that carry a value. */
data class MailRules(
  val rules: List<MailRule> = emptyList(),
  val pageLimit: Int? = null,
  val photoLimit: Int? = null,
  /** ISO 639-1 codes, lower case; empty means no language rule recorded. */
  val languages: List<String> = emptyList(),
) {
  val isEmpty: Boolean get() = rules.isEmpty() && pageLimit == null && photoLimit == null && languages.isEmpty()
  fun has(tag: String): Boolean = rules.any { it.tag == tag }

  /** Tags the app acts on. Everything else is display only. */
  val forbidsPhotos: Boolean get() = has(NO_PHOTOS)

  val languageNames: List<String> get() = languages.map { code ->
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }.replaceFirstChar { it.uppercase() }
  }

  /** Every rule as a display line: tags first, then the valued ones. */
  fun lines(): List<String> = rules.map { it.label } +
    listOfNotNull(
      pageLimit?.let { "At most $it page${if (it == 1) "" else "s"} per letter" },
      photoLimit?.let { "At most $it photo${if (it == 1) "" else "s"} per letter" },
      languageNames.takeIf { it.isNotEmpty() }?.let { "Accepted languages: ${it.joinToString(", ")}" },
    )

  companion object {
    const val NO_PHOTOS = "no_photos"
    const val NO_ENCLOSURES = "no_enclosures"
    const val HANDWRITTEN_ONLY = "handwritten_only"
    const val POSTCARDS_ONLY = "postcards_only"
    const val DIGITAL_MAIL_ONLY = "digital_mail_only"
    const val ORIGINALS_DESTROYED = "originals_destroyed"
    const val DELIVERY_NOT_CONFIRMED = "delivery_not_confirmed"
  }
}

/** Something the compose screen tells the writer because of the facility's rules. */
data class ComposeAdvice(val text: String, val warning: Boolean)

/**
 * What the rules mean for the letter being written. Pure, so it is unit tested.
 * Nothing here blocks sending: page counts are estimates and groups know their
 * facilities better than a tag does. The one hard effect (no image attachments
 * where pictures are refused) is enforced by the attachment picker, using
 * [MailRules.forbidsPhotos].
 */
fun composeAdvice(rules: MailRules, estimatedPages: Int, imageAttachments: Int): List<ComposeAdvice> = buildList {
  rules.pageLimit?.let { limit ->
    if (estimatedPages > limit) add(ComposeAdvice("This is about $estimatedPages pages, and this facility accepts at most $limit. Consider splitting it into two letters.", warning = true))
  }
  if (rules.languages.isNotEmpty()) {
    add(ComposeAdvice("Letters here must be written in ${rules.languageNames.joinToString(" or ")}. If that is not your language, ask your relay group about translation.", warning = false))
  }
  if (rules.forbidsPhotos) add(ComposeAdvice("This facility refuses pictures, so image attachments are turned off. A PDF can still be attached.", warning = false))
  rules.photoLimit?.let { limit ->
    if (imageAttachments > limit) add(ComposeAdvice("This facility accepts at most $limit photo${if (limit == 1) "" else "s"} per letter; you have attached $imageAttachments.", warning = true))
  }
  if (rules.has(MailRules.HANDWRITTEN_ONLY)) add(ComposeAdvice("Only handwritten letters are accepted here. Attach a scan of a handwritten letter, or tell your relay group in the note that it needs copying by hand.", warning = true))
  if (rules.has(MailRules.POSTCARDS_ONLY)) add(ComposeAdvice("Only postcards are accepted here. Keep it short enough to fit one.", warning = true))
  if (rules.has(MailRules.ORIGINALS_DESTROYED)) add(ComposeAdvice("Mail here is scanned and the original destroyed; the prisoner sees a copy.", warning = false))
  if (rules.has(MailRules.DELIVERY_NOT_CONFIRMED)) add(ComposeAdvice("Delivery to this facility cannot be confirmed. Do not send anything irreplaceable.", warning = false))
}

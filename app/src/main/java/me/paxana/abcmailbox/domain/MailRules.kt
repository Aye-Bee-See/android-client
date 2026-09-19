package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.Strings
import me.paxana.abcmailbox.R
import java.util.Locale

/** One tag from the API's mail-rule vocabulary, with its default English wording. */
data class MailRule(val tag: String, val category: String, val label: String, val description: String?) {
  /**
   * [label] and [description] are the server's wording, which is English. The app carries its own
   * translations keyed on the tag (`rule_<tag>`, `rule_<tag>_desc`), as the API intends ("a client with
   * its own translations keys them on `tag`"). In English the server's wording wins, because admins
   * may have reworded a rule; in any language a rule the app has never heard of reads in the server's words.
   */
  fun label(strings: Strings): String = if (strings.language == "en") label else strings.byName("rule_$tag") ?: label
  fun description(strings: Strings): String? = if (strings.language == "en") description else strings.byName("rule_${tag}_desc") ?: description
}

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

  /** The languages' names, in the language the app is speaking: "Spanish", "español", "испанский". */
  fun languageNames(inLanguage: String = "en"): List<String> = languages.map { code ->
    val display = Locale.forLanguageTag(inLanguage)
    Locale.forLanguageTag(code).getDisplayLanguage(display).ifBlank { code }.let { if (inLanguage == "en") it.replaceFirstChar { c -> c.uppercase() } else it }
  }

  /** Every rule as a display line: tags first, then the valued ones. */
  fun lines(strings: Strings): List<String> = rules.map { it.label(strings) } +
    listOfNotNull(
      pageLimit?.let { strings.plural(R.plurals.rule_page_limit, it) },
      photoLimit?.let { strings.plural(R.plurals.rule_photo_limit, it) },
      languageNames(strings.language).takeIf { it.isNotEmpty() }?.let { strings.get(R.string.rule_languages, it.joinToString(", ")) },
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
fun composeAdvice(rules: MailRules, estimatedPages: Int, imageAttachments: Int, strings: Strings): List<ComposeAdvice> = buildList {
  rules.pageLimit?.let { limit ->
    if (estimatedPages > limit) add(ComposeAdvice(strings.get(R.string.advice_too_long, estimatedPages, limit), warning = true))
  }
  if (rules.languages.isNotEmpty()) {
    add(ComposeAdvice(strings.get(R.string.advice_language, rules.languageNames(strings.language).joinToString(strings.get(R.string.list_or))), warning = false))
  }
  if (rules.forbidsPhotos) add(ComposeAdvice(strings.get(R.string.advice_no_photos), warning = false))
  rules.photoLimit?.let { limit ->
    if (imageAttachments > limit) add(ComposeAdvice(strings.plural(R.plurals.advice_too_many_photos, limit, imageAttachments), warning = true))
  }
  if (rules.has(MailRules.HANDWRITTEN_ONLY)) add(ComposeAdvice(strings.get(R.string.advice_handwritten), warning = true))
  if (rules.has(MailRules.POSTCARDS_ONLY)) add(ComposeAdvice(strings.get(R.string.advice_postcards), warning = true))
  if (rules.has(MailRules.ORIGINALS_DESTROYED)) add(ComposeAdvice(strings.get(R.string.advice_originals_destroyed), warning = false))
  if (rules.has(MailRules.DELIVERY_NOT_CONFIRMED)) add(ComposeAdvice(strings.get(R.string.advice_not_confirmed), warning = false))
}

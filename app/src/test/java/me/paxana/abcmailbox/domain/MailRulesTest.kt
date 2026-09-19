package me.paxana.abcmailbox.domain

import me.paxana.abcmailbox.text.TestStrings
import kotlinx.serialization.json.Json
import me.paxana.abcmailbox.data.api.ApiEnvelope
import me.paxana.abcmailbox.data.api.MailRuleVocabularyDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailRulesTest {
  private val catalog = MailRuleCatalog.Compiled
  private fun rules(vararg tags: String, pages: Int? = null, photos: Int? = null, languages: List<String> = emptyList()) =
    MailRules(catalog.resolveAll(tags.toList()), pages, photos, languages)

  @Test
  fun `the compiled vocabulary covers every tag the server publishes`() {
    // Fixture captured from GET /prison/mail-rules. If this fails, run tools/gen-mail-rules.py.
    val json = Json { ignoreUnknownKeys = true }
    val live = json.decodeFromString<ApiEnvelope<MailRuleVocabularyDto>>(javaClass.getResourceAsStream("/directory/mail-rules.json")!!.reader().readText()).data!!
    val missing = live.rules.map { it.tag } - CompiledMailRules.rules.map { it.tag }.toSet()
    assertEquals("tags missing from the compiled vocabulary", emptyList<String>(), missing)
    assertEquals(live.categories, CompiledMailRules.categories)
  }

  @Test
  fun `an unknown tag displays as readable text and breaks nothing`() {
    val r = catalog.resolve("no_glitter_pens")
    assertEquals("No glitter pens", r.label)
    val all = catalog.resolveAll(listOf("no_glitter_pens", "no_photos", "return_address_required", "no_photos"))
    assertEquals(listOf("return_address_required", "no_photos", "no_glitter_pens"), all.map { it.tag }) // vocabulary order, duplicates dropped, unknown last
  }

  @Test
  fun `lines show tags then the valued rules`() {
    assertEquals(
      listOf("Return address required", "No pictures", "At most 4 pages per letter", "At most 1 photo per letter", "Accepted languages: English, Spanish"),
      rules("no_photos", "return_address_required", pages = 4, photos = 1, languages = listOf("en", "es")).lines(TestStrings()),
    )
    assertTrue(MailRules().isEmpty)
    assertFalse(rules(pages = 2).isEmpty)
  }

  @Test
  fun `page limit warns only when the estimate exceeds it`() {
    val r = rules(pages = 2)
    assertTrue(composeAdvice(r, estimatedPages = 2, imageAttachments = 0, TestStrings()).isEmpty())
    val over = composeAdvice(r, estimatedPages = 3, imageAttachments = 0, TestStrings()).single()
    assertTrue(over.warning); assertTrue(over.text.contains("about 3 pages")); assertTrue(over.text.contains("at most 2"))
  }

  @Test
  fun `languages, pictures, photo limits and handwriting produce advice`() {
    val a = composeAdvice(rules("no_photos", "handwritten_only", languages = listOf("es")), 1, 0, TestStrings())
    assertTrue(a.any { it.text.contains("Spanish") && !it.warning })
    assertTrue(a.any { it.text.contains("refuses pictures") })
    assertTrue(a.any { it.text.contains("handwritten") && it.warning })
    val photos = composeAdvice(rules(photos = 2), 1, imageAttachments = 3, TestStrings()).single()
    assertTrue(photos.warning); assertTrue(photos.text.contains("at most 2 photos"))
    assertTrue(composeAdvice(MailRules(), 10, 5, TestStrings()).isEmpty())
  }

  @Test
  fun `only no_photos turns image attachments off`() {
    assertTrue(rules("no_photos").forbidsPhotos)
    assertFalse(rules("no_polaroids", "no_explicit_photos").forbidsPhotos)
  }
}

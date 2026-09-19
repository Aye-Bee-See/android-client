package me.paxana.abcmailbox.text

import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.domain.MailRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Mistakes in a translation do not show up for the person who made them: they crash, or read wrongly,
 * only on a phone set to that language. These tests read the resource files themselves.
 */
class TranslationsTest {
  private val languages = listOf("es", "ru")
  /** Names and the envelope's name line: the same in every language, on purpose. */
  private val untranslated = setOf("app_name", "name_with_number")

  private class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

  private fun read(folder: String): Resources {
    val dir = sequenceOf("src/main/res/$folder", "app/src/main/res/$folder").map(::File).first { it.isDirectory }
    val strings = mutableMapOf<String, String>(); val plurals = mutableMapOf<String, Map<String, String>>()
    dir.listFiles { f -> f.extension == "xml" }!!.forEach { file ->
      val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
      for (i in 0 until nodes.length) {
        val e = nodes.item(i) as? Element ?: continue
        when (e.tagName) {
          "string" -> strings[e.getAttribute("name")] = e.textContent
          "plurals" -> plurals[e.getAttribute("name")] = buildMap {
            val items = e.getElementsByTagName("item")
            for (j in 0 until items.length) (items.item(j) as Element).let { put(it.getAttribute("quantity"), it.textContent) }
          }
        }
      }
    }
    return Resources(strings, plurals)
  }

  /** `%1$s`, `%2$d`, `%d`…: what the code will supply, which every language must consume identically. */
  private fun placeholders(text: String): List<String> = Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()

  @Test
  fun `every string and plural exists in every language`() {
    val english = read("values")
    for (lang in languages) {
      val other = read("values-$lang")
      assertEquals("strings missing from $lang", emptySet<String>(), english.strings.keys - other.strings.keys - untranslated)
      assertEquals("plurals missing from $lang", emptySet<String>(), english.plurals.keys - other.plurals.keys)
      assertEquals("$lang has strings English does not", emptySet<String>(), other.strings.keys - english.strings.keys)
    }
  }

  @Test
  fun `a translation takes exactly the placeholders the English does`() {
    val english = read("values")
    for (lang in languages) {
      val other = read("values-$lang")
      english.strings.forEach { (name, text) -> other.strings[name]?.let { assertEquals("$lang/$name", placeholders(text), placeholders(it)) } }
      english.plurals.forEach { (name, forms) ->
        val expected = placeholders(forms.getValue("other"))
        other.plurals[name]?.forEach { (quantity, text) ->
          // Spanish, like English, may spell the singular out ("una carta") and drop the number. Russian may not:
          // its "one" form is also used for 21, 31, 101…, so a Russian singular without the number would be wrong.
          if (quantity == "one" && lang != "ru") assertTrue("$lang/$name[one] uses a placeholder English does not supply", expected.containsAll(placeholders(text)))
          else assertEquals("$lang/$name[$quantity]", expected, placeholders(text))
        }
      }
    }
  }

  @Test
  fun `Russian plurals have all four forms, and the others at least two`() {
    read("values-ru").plurals.forEach { (name, forms) -> assertEquals("ru/$name", setOf("one", "few", "many", "other"), forms.keys) }
    for (folder in listOf("values", "values-es")) read(folder).plurals.forEach { (name, forms) -> assertTrue("$folder/$name", forms.keys.containsAll(setOf("one", "other"))) }
  }

  @Test
  fun `Russian counts letters the way Russian does`() {
    val ru = TestStrings("ru")
    assertEquals(listOf("1 письмо", "2 письма", "5 писем", "11 писем", "21 письмо", "22 письма", "112 писем"),
      listOf(1, 2, 5, 11, 21, 22, 112).map { ru.plural(R.plurals.thread_count_letters, it) })
    assertEquals("3 cartas", TestStrings("es").plural(R.plurals.thread_count_letters, 3))
  }

  @Test
  fun `a mail rule reads in the user's language when the app knows its tag, and in the server's words when it does not`() {
    val known = MailRule("no_photos", "photos", "No pictures", "Letters must be text only; photographs and printed images are returned.")
    assertEquals("No pictures", known.label(TestStrings()))
    assertEquals("Sin imágenes", known.label(TestStrings("es")))
    assertEquals("Без изображений", known.label(TestStrings("ru")))
    assertTrue(known.description(TestStrings("ru"))!!.startsWith("Письма должны содержать только текст"))

    val addedByAnAdmin = MailRule("no_glitter_or_stickers", "content", "No glitter or stickers", "Returned to sender.")
    assertEquals("No glitter or stickers", addedByAnAdmin.label(TestStrings("ru")))
    // In English the server's wording wins even for a known tag: an admin may have reworded it.
    assertEquals("Absolutely no pictures", known.copy(label = "Absolutely no pictures").label(TestStrings()))
  }

  @Test
  fun `sentences built from rules come out whole in each language`() {
    val es = TestStrings("es"); val ru = TestStrings("ru")
    assertEquals("Son unas 6 páginas, y este centro acepta como máximo 4. Piensa en dividirla en dos cartas.", es.get(R.string.advice_too_long, 6, 4))
    assertEquals("Не более 1 страницы на письмо", ru.plural(R.plurals.rule_page_limit, 1))
    assertEquals("Не более 5 страниц на письмо", ru.plural(R.plurals.rule_page_limit, 5))
  }
}

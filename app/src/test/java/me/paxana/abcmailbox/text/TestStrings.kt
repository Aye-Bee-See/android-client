package me.paxana.abcmailbox.text

import me.paxana.abcmailbox.R
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Strings] for JVM tests: reads the real resource files, so tests assert the sentences people see.
 * `TestStrings()` is English; `TestStrings("ru")` reads `values-ru`, which lets a test check that a
 * translation exists and that its plural forms are chosen as the language demands.
 */
class TestStrings(override val language: String = "en") : Strings {
  private val strings = mutableMapOf<String, String>()
  private val plurals = mutableMapOf<String, Map<String, String>>()
  private val stringNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
  private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }

  init {
    // English first, then the language on top: a string missing from a translation falls back, as on a phone.
    load("values"); if (language != "en") load("values-$language")
  }

  private fun load(dir: String) {
    val folder = sequenceOf("src/main/res/$dir", "app/src/main/res/$dir").map(::File).firstOrNull { it.isDirectory } ?: return
    folder.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
      val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
      val nodes = root.childNodes
      for (i in 0 until nodes.length) {
        val e = nodes.item(i) as? Element ?: continue
        when (e.tagName) {
          "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
          "plurals" -> plurals[e.getAttribute("name")] = buildMap {
            val items = e.getElementsByTagName("item")
            for (j in 0 until items.length) (items.item(j) as Element).let { put(it.getAttribute("quantity"), unescape(it.textContent)) }
          }
        }
      }
    }
  }

  private fun unescape(raw: String) = raw.trim().replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")

  override fun get(id: Int, vararg args: Any): String {
    val text = strings[stringNames[id]] ?: error("no string named ${stringNames[id]} (id $id)")
    return if (args.isEmpty()) text else text.format(*args)
  }

  override fun plural(id: Int, count: Int, vararg args: Any): String {
    val forms = plurals[pluralNames[id]] ?: error("no plurals named ${pluralNames[id]}")
    val text = forms[quantity(count)] ?: forms.getValue("other")
    return text.format(count, *args)
  }

  override fun byName(name: String): String? = strings[name]

  /** The CLDR plural rules for the three languages the app ships, for whole numbers. */
  private fun quantity(n: Int): String = when (language) {
    "ru" -> when {
      n % 10 == 1 && n % 100 != 11 -> "one"
      n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
      else -> "many"
    }
    else -> if (n == 1) "one" else "other" // English and Spanish
  }
}

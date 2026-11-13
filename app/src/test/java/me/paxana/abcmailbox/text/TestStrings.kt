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

  /**
   * What the build (aapt2) makes of a string's text, so that a test sees the sentence the phone shows. Outside double
   * quotes, a run of spaces or line breaks becomes one space and the ones at either end are dropped; inside them the
   * text is kept as written; a double quote that is not escaped is taken for that marking and does not appear; and a
   * backslash escapes the next character. Before 12 November 2026 this only trimmed and unescaped, and a test could
   * read a string with its quotes in that the phone showed without them.
   */
  private fun unescape(raw: String): String {
    val out = StringBuilder()
    var quoted = false
    var gap = false
    fun put(c: Char) { if (gap && out.isNotEmpty()) out.append(' '); gap = false; out.append(c) }
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      when {
        c == '\\' && i + 1 < raw.length -> when (val next = raw[++i]) {
          'n' -> put('\n')
          't' -> put('\t')
          'u' -> { put(raw.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
          else -> put(next)
        }
        c == '"' -> quoted = !quoted
        !quoted && c.isWhitespace() -> gap = true
        else -> put(c)
      }
      i++
    }
    return out.toString()
  }

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

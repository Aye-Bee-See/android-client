package me.paxana.abcmailbox.ui.common

import java.net.URI

/**
 * A link the directory holds (a group's website, a social page, a prisoner's support site), as something this phone
 * may be asked to open: `http`, `https` and `mailto` only. Written without a scheme, as people do ("abcportland.org"),
 * it is taken as https. Anything else (`tel:`, another app's own scheme, this app's own `abcmailbox://`, or text that
 * is no address at all) answers null and is shown as words, not offered as a link: the directory's fields are
 * typed by group admins, and a tap must not start whatever an address names.
 */
fun directoryLink(raw: String): String? {
  val text = raw.trim()
  if (text.isEmpty() || text.any { it.isWhitespace() }) return null
  val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(text)?.value?.dropLast(1)?.lowercase()
  val link = when (scheme) {
    null -> if (text.count { it == '@' } == 1 && !text.contains('/')) "mailto:$text" else "https://$text"
    "http", "https", "mailto" -> text
    else -> return null
  }
  val uri = runCatching { URI(link) }.getOrNull() ?: return null
  // Schemes are case-insensitive ("MAILTO:" is mail), and a mail link needs someone to write to: "mailto:" and
  // "mailto:?subject=…" address nobody and would open the mail app on an empty letter.
  val sound = when (uri.scheme?.lowercase()) {
    "mailto" -> uri.rawSchemeSpecificPart.substringBefore('?').isNotBlank()
    else -> !uri.host.isNullOrEmpty()
  }
  return link.takeIf { sound }
}

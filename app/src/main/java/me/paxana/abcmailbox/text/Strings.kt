package me.paxana.abcmailbox.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's words, for code that is not a composable. A screen calls `stringResource()`; a ViewModel,
 * a repository or a domain rule ("this is about 6 pages, and this facility accepts at most 4") has no
 * Android `Context` to ask, and should not need one to be tested. They ask this instead.
 *
 * Production reads Android resources, in the user's language. Tests read the same `strings.xml` from
 * disk (see `TestStrings`), so a test still asserts the sentence a person would see.
 */
interface Strings {
  fun get(@StringRes id: Int, vararg args: Any): String
  /** Picks the form the language needs for [count]: English has two, Russian four. [count] is also the first format argument. */
  fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
  /** For words keyed by something the server sends (a mail-rule tag, a service key). Null when the app has no such word. */
  fun byName(name: String): String?
  /** "en", "es", "ru": which language the words above come in. */
  val language: String
}

@Singleton
class AndroidStrings @Inject constructor(@ApplicationContext private val context: Context) : Strings {
  override fun get(id: Int, vararg args: Any): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
  override fun plural(id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, count, *args)
  // Looked up by name at run time, so the resource shrinker cannot see the use: res/raw/keep.xml lists the prefixes.
  @Suppress("DiscouragedApi")
  override fun byName(name: String): String? = context.resources.getIdentifier(name, "string", context.packageName).takeIf { it != 0 }?.let(context::getString)
  override val language: String get() = context.resources.configuration.locales[0].language
}

/** The same thing inside a composable, for calling domain functions that build sentences. Follows language changes. */
@Composable
fun rememberStrings(): Strings {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  return remember(context, configuration) { AndroidStrings(context) }
}

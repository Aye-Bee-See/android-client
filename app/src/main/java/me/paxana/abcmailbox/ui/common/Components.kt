package me.paxana.abcmailbox.ui.common

import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import me.paxana.abcmailbox.data.api.AppError

/**
 * A detail page: title bar with a back arrow, content below. This Scaffold sits
 * inside the app shell's Scaffold, which already pads for the status bar and
 * the bottom navigation, so this one must not apply window insets again or the
 * title floats below a blank band.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.asHeading()) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        windowInsets = WindowInsets(0, 0, 0, 0),
      )
    },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = MaterialTheme.colorScheme.background,
    content = content,
  )
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = { Text(placeholder) },
    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { }),
    modifier = modifier.fillMaxWidth(),
  )
}

/** One row of mutually exclusive filter chips; `null` is "All". */
@Composable
fun <T> ChipRow(
  options: List<Pair<T, String>>,
  selected: T?,
  onSelect: (T?) -> Unit,
  modifier: Modifier = Modifier,
  allLabel: String = stringResource(R.string.filter_all),
  showAll: Boolean = true,
) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (showAll) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) })
    options.forEach { (value, label) ->
      FilterChip(selected = selected == value, onClick = { onSelect(if (selected == value && showAll) null else value) }, label = { Text(label) })
    }
  }
}

/** A small rounded label, like the interest tags on the site. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TagRow(tags: List<String>, modifier: Modifier = Modifier) {
  if (tags.isEmpty()) return
  FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    tags.forEach { Tag(it) }
  }
}

/** The red-wash notice used for status notices and stale-verification warnings. */
@Composable
fun AlertBanner(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onErrorContainer,
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.errorContainer)
      .padding(12.dp),
  )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  // Marked as a heading: a screen-reader user moves through a long page heading by heading, as a sighted one skims.
  Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 8.dp).semantics { heading() })
}

/** A page's main title. Same reason as [SectionTitle]: it is a landmark, not just big text. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/**
 * An error under a form. It appears after the fact, somewhere the user is not looking, so it is a
 * live region: TalkBack reads it out when it shows up instead of leaving the user to go and find it.
 */
@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
  Text(message, color = MaterialTheme.colorScheme.error, style = style, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
fun KeyValue(label: String, value: String?) {
  if (value.isNullOrBlank()) return
  Column(Modifier.padding(vertical = 4.dp)) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyLarge)
  }
}

/** A tappable list row with a title, a subtitle, and optional tags. */
@Composable
fun RecordRow(
  title: String,
  subtitle: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  notice: String? = null,
  tags: List<String> = emptyList(),
  secondary: String? = null,
  horizontalPadding: Dp = 20.dp,
) {
  Column(
    modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = horizontalPadding, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    secondary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.spokenWithoutArrows(it)) }
    TagRow(tags)
  }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(error: AppError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(error.readable(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
  }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().wrapContentHeight().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** A sentence for any [AppError], including the ones that carry no server text. */
@Composable
fun AppError.readable(): String = when (this) {
  is AppError.Network -> stringResource(R.string.error_network)
  is AppError.NotFound -> info ?: stringResource(R.string.error_not_found)
  // The server's own sentence when it sent one: it is more specific than anything the app could say.
  else -> userMessage ?: stringResource(R.string.error_generic)
}

/**
 * Shows text in upper case without changing what is stored. Rewriting a field's
 * value inside `onValueChange` (for example `it.uppercase()`) fights the keyboard's
 * composing state and can drop keystrokes; transforming only the display cannot.
 * The mapping is the identity because upper-casing these ASCII codes keeps length.
 */
object UppercaseTransformation : androidx.compose.ui.text.input.VisualTransformation {
  override fun filter(text: androidx.compose.ui.text.AnnotatedString) =
    androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(text.text.uppercase()), androidx.compose.ui.text.input.OffsetMapping.Identity)
}

/** A facility's mail rules as a bulleted list, tags first, then page and photo limits and languages. */
@Composable
fun MailRulesList(rules: me.paxana.abcmailbox.domain.MailRules, emptyText: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (rules.isEmpty) Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rules.lines().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
  }
}

/**
 * The arrows in "← Received" and "→ Sent" are a visual cue; the word beside them already says it.
 * A screen reader would announce "leftwards arrow", so the spoken form leaves them out.
 */
fun Modifier.spokenWithoutArrows(text: String): Modifier =
  if (text.any { it == '←' || it == '→' }) semantics { contentDescription = text.replace("← ", "").replace("→ ", "") } else this

/**
 * A file attached to a letter. At least 48dp tall (the minimum comfortable touch target; the text
 * alone is about 20dp), announced as a button with what it does, and the paperclip is decoration.
 */
@Composable
fun AttachmentRow(name: String, sizeLabel: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_open_file), onClick = onOpen),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("📎", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics { })
    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
    Text(sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * A claim token or recovery code, large and monospaced for copying by hand. Read aloud one
 * character at a time, in its groups of four: as a word, "7ND4" is noise; "7, N, D, 4" can be written down.
 */
@Composable
fun SecretCodeText(code: String, modifier: Modifier = Modifier) {
  val pretty = me.paxana.abcmailbox.crypto.SecretCodes.pretty(code)
  Text(
    pretty.chunked(15).joinToString("\n") { it.trim('-') },
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 26.sp, lineHeight = 38.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 20.dp)
      .semantics { contentDescription = pretty.split('-').joinToString(". ") { group -> group.toList().joinToString(" ") } },
  )
}

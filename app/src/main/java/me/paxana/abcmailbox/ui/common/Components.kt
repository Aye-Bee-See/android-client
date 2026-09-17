package me.paxana.abcmailbox.ui.common

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
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
  allLabel: String = "All",
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) })
    options.forEach { (value, label) ->
      FilterChip(selected = selected == value, onClick = { onSelect(if (selected == value) null else value) }, label = { Text(label) })
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
  Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 8.dp))
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
      .clickable(onClick = onClick)
      .padding(horizontal = horizontalPadding, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    secondary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
    Button(onClick = onRetry) { Text("Try again") }
  }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().wrapContentHeight().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** A sentence for any [AppError], including the ones that carry no server text. */
fun AppError.readable(): String = when (this) {
  is AppError.Network -> "Can't reach the server. Check your connection and try again."
  is AppError.NotFound -> info ?: "That record doesn't exist or is not public."
  else -> userMessage ?: "Something went wrong. Please try again."
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

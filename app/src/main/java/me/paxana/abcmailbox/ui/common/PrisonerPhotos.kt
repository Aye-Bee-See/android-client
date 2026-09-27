package me.paxana.abcmailbox.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.paxana.abcmailbox.BuildConfig
import me.paxana.abcmailbox.R
import me.paxana.abcmailbox.domain.PrisonerPhoto

/**
 * The API address in use now, for resolving a hosted photo's path. Provided by the activity from the developer
 * override; the build's own address otherwise (previews, tests).
 */
val LocalApiBase = staticCompositionLocalOf<() -> String> { { BuildConfig.API_BASE_URL } }

/** An image request keyed on when the picture last changed, so a replaced photo is never shown from the cache. */
@Composable
private fun photoRequest(photo: PrisonerPhoto): ImageRequest? {
  val base = LocalApiBase.current()
  val url = photo.absoluteUrl(base) ?: return null
  val key = photo.cacheKey(base)
  return ImageRequest.Builder(LocalContext.current).data(url).diskCacheKey(key).memoryCacheKey(key).build()
}

/** A round thumbnail for a list row: the photo over the person's initials, which show while it loads and when it cannot. */
@Composable
fun PrisonerAvatar(name: String, photo: PrisonerPhoto?, size: Dp = 48.dp) {
  Box(
    Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    Text(PrisonerPhoto.initials(name), style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.36f).sp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    // Decorative: the name is read out beside it.
    photo?.let { photoRequest(it) }?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("prisoner-avatar-photo")) }
  }
}

/** The picture on a prisoner's page, with its credit. One that cannot be fetched (offline, say) says so in a line, never an empty box. */
@Composable
fun PrisonerPhotoBlock(name: String, photo: PrisonerPhoto) {
  var failed by remember(photo.path) { mutableStateOf(false) }
  val request = photoRequest(photo)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (failed || request == null) {
      Text(stringResource(R.string.photo_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("prisoner-photo-failed"))
      return@Column
    }
    AsyncImage(
      model = request, contentDescription = stringResource(R.string.photo_of, name), contentScale = ContentScale.Crop, onError = { failed = true },
      modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("prisoner-photo"),
    )
    photo.credit?.let { Text(stringResource(R.string.photo_credit, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("prisoner-photo-credit")) }
  }
}

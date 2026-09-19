package me.paxana.abcmailbox.ui.directory

import me.paxana.abcmailbox.ui.common.asHeading
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.RecordRow
import me.paxana.abcmailbox.ui.common.SectionTitle

/** The Directory tab's front page, after `index.html`: the pitch, three doors, featured prisoners. */
@Composable
fun DirectoryHomeScreen(
  onPrisoners: () -> Unit,
  onFacilities: () -> Unit,
  onGroups: () -> Unit,
  onPrisoner: (Int) -> Unit,
  viewModel: DirectoryHomeViewModel = hiltViewModel(),
) {
  val featured by viewModel.featured.collectAsStateWithLifecycle()

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Letters matter.", style = MaterialTheme.typography.displaySmall, modifier = Modifier.asHeading())
      Text("Write one today.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
      Text(
        "Browse prisoner profiles, find a support group near you, and learn exactly what each facility requires before you write.",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    HorizontalDivider()
    DoorRow("Prisoners", "Profiles maintained by support groups", onPrisoners)
    HorizontalDivider()
    DoorRow("Facilities", "Mail rules and routing for each prison", onFacilities)
    HorizontalDivider()
    DoorRow("Groups", "Chapters collecting and relaying letters", onGroups)
    HorizontalDivider()

    SectionTitle("Prisoners seeking correspondence", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    when (val f = featured) {
      is Loadable.Loading -> LoadingBox()
      is Loadable.Failed -> ErrorBox(f.error, onRetry = viewModel::load)
      is Loadable.Loaded -> if (f.value.isEmpty()) {
        Text("No featured prisoners yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
      } else {
        f.value.forEach { p -> PrisonerRow(p, onClick = { onPrisoner(p.id) }); HorizontalDivider() }
      }
    }
  }
}

@Composable
private fun DoorRow(title: String, subtitle: String, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
  }
}

@Composable
fun PrisonerRow(p: me.paxana.abcmailbox.domain.Prisoner, onClick: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp) {
  val heldAt = p.facility?.let { "Held at: ${it.name}" + (it.shortLocation.takeIf { s -> s.isNotBlank() }?.let { s -> ", $s" } ?: "") }
  val since = p.detainedSince?.year?.let { "Since: $it" }
  RecordRow(
    title = p.name,
    secondary = p.birthName,
    subtitle = listOfNotNull(heldAt, since, "Est. release: ${p.releaseSummary}").joinToString("  ·  "),
    notice = p.statusNotice,
    tags = p.interests.take(4),
    onClick = onClick,
    horizontalPadding = horizontalPadding,
  )
}

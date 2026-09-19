package me.paxana.abcmailbox.ui.directory

import me.paxana.abcmailbox.ui.common.asHeading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.NetworkRoles
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.domain.Services
import me.paxana.abcmailbox.ui.common.AlertBanner
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.ErrorBox
import me.paxana.abcmailbox.ui.common.KeyValue
import me.paxana.abcmailbox.ui.common.LoadingBox
import me.paxana.abcmailbox.ui.common.MailRulesList
import me.paxana.abcmailbox.ui.common.SectionTitle
import me.paxana.abcmailbox.ui.common.TagRow
import me.paxana.abcmailbox.ui.common.long
import me.paxana.abcmailbox.ui.common.verificationLine

@Composable
fun PrisonerScreen(
  onBack: () -> Unit,
  onFacility: (Int) -> Unit,
  onGroup: (Int) -> Unit,
  onWrite: (Int) -> Unit,
  viewModel: PrisonerViewModel = hiltViewModel(),
) {
  val state by viewModel.prisoner.collectAsStateWithLifecycle()
  val facility by viewModel.facility.collectAsStateWithLifecycle()

  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: "Prisoner", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (val s = state) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(s.error, onRetry = viewModel::load)
        is Loadable.Loaded -> PrisonerBody(
          p = s.value,
          facilityDetail = facility,
          onFacility = onFacility,
          onGroup = onGroup,
          onWrite = { onWrite(s.value.id) },
        )
      }
    }
  }
}

@Composable
private fun PrisonerBody(p: Prisoner, facilityDetail: Facility?, onFacility: (Int) -> Unit, onGroup: (Int) -> Unit, onWrite: () -> Unit) {
  val facility = facilityDetail ?: p.facility
  Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    p.statusNotice?.let { AlertBanner("⚠ $it") }
    p.photoUrl?.let {
      AsyncImage(model = it, contentDescription = "Photo of ${p.name}", modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(4.dp)))
    }
    Text(p.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    val alsoKnown = listOfNotNull(p.birthName) + p.aliases
    if (alsoKnown.isNotEmpty()) Text(alsoKnown.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)

    Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text("Write a letter") }

    KeyValue("Country", p.country)
    facility?.let { f ->
      Column(Modifier.padding(vertical = 4.dp)) {
        Text("CURRENT FACILITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onFacility(f.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
          Text(f.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
        f.addressLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
      }
    }
    KeyValue("Detained since", p.detainedSince?.long())
    KeyValue("Sentence", p.sentence)
    KeyValue("Est. release", p.releaseSummary)
    KeyValue("Charge(s)", p.charges)
    Text(verificationLine(p.verification.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    p.bio?.let { SectionTitle("About"); Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (p.interests.isNotEmpty()) { SectionTitle("Interests"); TagRow(p.interests) }

    facility?.let { f ->
      if (!f.rules.isEmpty) {
        SectionTitle("Facility mail rules")
        MailRulesList(f.rules, emptyText = "")
      }
      if (f.routing.explanation.isNotBlank()) {
        Text("${f.routing.label}. ${f.routing.explanation}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    p.supportWebsite?.let { url ->
      SectionTitle("Support")
      val uriHandler = LocalUriHandler.current
      TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("🔗 $url", color = MaterialTheme.colorScheme.secondary)
      }
    }
    p.donationInfo?.let { SectionTitle("Donate / commissary"); Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (p.supportGroups.isNotEmpty()) {
      SectionTitle("Support groups")
      p.supportGroups.forEach { g ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
          Text(g.name, style = MaterialTheme.typography.titleMedium)
          g.supportDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          TextButton(onClick = { onGroup(g.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("View group") }
        }
      }
    }
  }
}

@Composable
fun FacilityScreen(
  onBack: () -> Unit,
  onPrisoner: (Int) -> Unit,
  onGroup: (Int) -> Unit,
  viewModel: FacilityViewModel = hiltViewModel(),
) {
  val state by viewModel.facility.collectAsStateWithLifecycle()
  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: "Facility", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (val s = state) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(s.error, onRetry = viewModel::load)
        is Loadable.Loaded -> FacilityBody(s.value, onPrisoner, onGroup)
      }
    }
  }
}

@Composable
private fun FacilityBody(f: Facility, onPrisoner: (Int) -> Unit, onGroup: (Int) -> Unit) {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (f.verification.isStale()) {
      AlertBanner("⚠ This record has not been verified in over 6 months. Mail rules and routing details may have changed.")
    }
    Text(f.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    f.shortLocation.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    if (f.addressLines.isNotEmpty()) {
      Column(Modifier.padding(vertical = 4.dp)) {
        Text("MAILING ADDRESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        f.addressLines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        f.country?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
      }
    }
    KeyValue("Routing", f.routing.label)
    Text(f.routing.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    KeyValue("Scan service", f.scanService)
    KeyValue("Notes", f.notes)
    Text(verificationLine(f.verification.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionTitle("Mail rules")
    MailRulesList(f.rules, emptyText = "No rules recorded. Confirm with a support group before writing.")
    f.rules.rules.filter { !it.description.isNullOrBlank() }.takeIf { it.isNotEmpty() }?.let { explained ->
      Text("What these mean", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
      explained.forEach { Text("${it.label}: ${it.description}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    SectionTitle("Relay groups")
    if (f.relayGroups.isEmpty()) Text("No relay group. Letters go directly to the facility.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    f.relayGroups.forEach { g ->
      TextButton(onClick = { onGroup(g.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(g.name + g.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.secondary)
      }
    }

    SectionTitle("Prisoners currently held")
    if (f.prisoners.isEmpty()) Text("None listed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    f.prisoners.forEach { p ->
      HorizontalDivider()
      PrisonerRow(p, onClick = { onPrisoner(p.id) }, horizontalPadding = 0.dp)
    }
  }
}

@Composable
fun GroupScreen(
  onBack: () -> Unit,
  onPrisoner: (Int) -> Unit,
  onFacility: (Int) -> Unit,
  viewModel: GroupViewModel = hiltViewModel(),
) {
  val state by viewModel.group.collectAsStateWithLifecycle()
  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: "Group", onBack = onBack) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (val s = state) {
        is Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBox(s.error, onRetry = viewModel::load)
        is Loadable.Loaded -> GroupBody(s.value, onPrisoner, onFacility)
      }
    }
  }
}

@Composable
private fun GroupBody(g: Group, onPrisoner: (Int) -> Unit, onFacility: (Int) -> Unit) {
  val uriHandler = LocalUriHandler.current
  Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    g.announcement?.let { AlertBanner(it) }
    Text(g.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    g.location.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    val links = buildList {
      g.website?.let { add("🔗 ${it.removePrefix("https://").removePrefix("http://")}" to it) }
      g.email?.let { add("✉ $it" to "mailto:$it") }
      g.socialLinks.forEach { (k, v) -> add("${k.replaceFirstChar { c -> c.uppercase() }}" to v) }
    }
    links.forEach { (label, url) ->
      TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, color = MaterialTheme.colorScheme.secondary)
      }
    }

    TagRow(g.services.map { Services.label(it) })
    Text(NetworkRoles.label(g.networkRole), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    g.about?.let { SectionTitle("About"); Text(it, style = MaterialTheme.typography.bodyLarge) }

    if (g.supportedPrisoners.isNotEmpty()) {
      SectionTitle("Prisoners we support")
      g.supportedPrisoners.forEach { p -> HorizontalDivider(); PrisonerRow(p, onClick = { onPrisoner(p.id) }, horizontalPadding = 0.dp) }
    }
    if (g.relayPrisons.isNotEmpty()) {
      SectionTitle("Facilities we mail to")
      g.relayPrisons.forEach { f ->
        Row(Modifier.fillMaxWidth()) {
          TextButton(onClick = { onFacility(f.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(f.name + f.shortLocation.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.secondary)
          }
        }
      }
    }
  }
}

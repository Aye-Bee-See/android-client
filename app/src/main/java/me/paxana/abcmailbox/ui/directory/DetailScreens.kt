package me.paxana.abcmailbox.ui.directory

import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
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

  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: stringResource(R.string.title_prisoner), onBack = onBack) { padding ->
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
      AsyncImage(model = it, contentDescription = stringResource(R.string.photo_of, p.name), modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(4.dp)))
    }
    Text(p.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    val alsoKnown = listOfNotNull(p.birthName) + p.aliases
    if (alsoKnown.isNotEmpty()) Text(alsoKnown.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)

    Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_write_letter)) }

    KeyValue(stringResource(R.string.label_country), p.country)
    facility?.let { f ->
      Column(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.label_current_facility), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onFacility(f.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
          Text(f.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
        f.addressLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
      }
    }
    KeyValue(stringResource(R.string.label_detained_since), p.detainedSince?.long())
    KeyValue(stringResource(R.string.label_sentence), p.sentence)
    KeyValue(stringResource(R.string.label_est_release), p.releaseSummary ?: stringResource(R.string.release_unknown))
    KeyValue(stringResource(R.string.label_charges), p.charges)
    Text(verificationLine(p.verification.at, rememberStrings()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    p.bio?.let { SectionTitle(stringResource(R.string.section_about)); Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (p.interests.isNotEmpty()) { SectionTitle(stringResource(R.string.section_interests)); TagRow(p.interests) }

    facility?.let { f ->
      if (!f.rules.isEmpty) {
        SectionTitle(stringResource(R.string.section_facility_rules))
        MailRulesList(f.rules, emptyText = "")
      }
      run {
        Text(stringResource(R.string.routing_sentence, stringResource(f.routing.labelRes), stringResource(f.routing.explanationRes)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    p.supportWebsite?.let { url ->
      SectionTitle(stringResource(R.string.section_support))
      val uriHandler = LocalUriHandler.current
      TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("🔗 $url", color = MaterialTheme.colorScheme.secondary)
      }
    }
    p.donationInfo?.let { SectionTitle(stringResource(R.string.section_donate)); Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (p.supportGroups.isNotEmpty()) {
      SectionTitle(stringResource(R.string.section_support_groups))
      p.supportGroups.forEach { g ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
          Text(g.name, style = MaterialTheme.typography.titleMedium)
          g.supportDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          TextButton(onClick = { onGroup(g.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.action_view_group)) }
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
  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: stringResource(R.string.title_facility), onBack = onBack) { padding ->
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
      AlertBanner(stringResource(R.string.stale_record_banner))
    }
    Text(f.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.asHeading())
    f.shortLocation.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    if (f.addressLines.isNotEmpty()) {
      Column(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.label_mailing_address), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        f.addressLines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        f.country?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
      }
    }
    KeyValue(stringResource(R.string.label_routing), stringResource(f.routing.labelRes))
    Text(stringResource(f.routing.explanationRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    KeyValue(stringResource(R.string.label_scan_service), f.scanService)
    KeyValue(stringResource(R.string.label_notes), f.notes)
    Text(verificationLine(f.verification.at, rememberStrings()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionTitle(stringResource(R.string.section_mail_rules))
    MailRulesList(f.rules, emptyText = stringResource(R.string.rules_none_recorded))
    f.rules.rules.filter { !it.description.isNullOrBlank() }.takeIf { it.isNotEmpty() }?.let { explained ->
      Text(stringResource(R.string.rules_what_they_mean), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
      val st = rememberStrings()
      explained.forEach { Text(stringResource(R.string.rule_with_meaning, it.label(st), it.description(st).orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    SectionTitle(stringResource(R.string.section_relay_groups))
    if (f.relayGroups.isEmpty()) Text(stringResource(R.string.relay_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
    f.relayGroups.forEach { g ->
      TextButton(onClick = { onGroup(g.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(g.name + g.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = MaterialTheme.colorScheme.secondary)
      }
    }

    SectionTitle(stringResource(R.string.section_prisoners_held))
    if (f.prisoners.isEmpty()) Text(stringResource(R.string.none_listed), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
  DetailScaffold(title = (state as? Loadable.Loaded)?.value?.name ?: stringResource(R.string.title_group), onBack = onBack) { padding ->
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

    TagRow(rememberStrings().let { st -> g.services.map { Services.label(it, st) } })
    Text(stringResource(NetworkRoles.labelRes(g.networkRole)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    g.about?.let { SectionTitle(stringResource(R.string.section_about)); Text(it, style = MaterialTheme.typography.bodyLarge) }

    if (g.supportedPrisoners.isNotEmpty()) {
      SectionTitle(stringResource(R.string.section_prisoners_we_support))
      g.supportedPrisoners.forEach { p -> HorizontalDivider(); PrisonerRow(p, onClick = { onPrisoner(p.id) }, horizontalPadding = 0.dp) }
    }
    if (g.relayPrisons.isNotEmpty()) {
      SectionTitle(stringResource(R.string.section_facilities_we_mail))
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

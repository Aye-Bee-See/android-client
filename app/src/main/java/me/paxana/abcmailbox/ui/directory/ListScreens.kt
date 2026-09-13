package me.paxana.abcmailbox.ui.directory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.Routing
import me.paxana.abcmailbox.domain.Services
import me.paxana.abcmailbox.ui.common.ChipRow
import me.paxana.abcmailbox.ui.common.DetailScaffold
import me.paxana.abcmailbox.ui.common.PagedList
import me.paxana.abcmailbox.ui.common.RecordRow
import me.paxana.abcmailbox.ui.common.SearchField

private val prisonerStatuses = listOf("incarcerated" to "Incarcerated", "pretrial" to "Awaiting trial", "free" to "Released")
private val routings = Routing.entries.filter { it != Routing.UNKNOWN }.map { it.key to it.label }
private val networkRoles = listOf("collecting" to "Collecting letters", "relay" to "Mailing relay")

@Composable
fun PrisonersScreen(
  onBack: () -> Unit,
  onPrisoner: (Int) -> Unit,
  title: String = "Political prisoners",
  viewModel: PrisonersViewModel = hiltViewModel(),
) {
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  DetailScaffold(title = title, onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = "No prisoners match.",
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, "Search by name")
            ChipRow(prisonerStatuses, filter.status, viewModel::setStatus, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf(true to "Featured"), filter.featured, viewModel::setFeatured, allLabel = "Everyone", modifier = Modifier.padding(top = 4.dp))
          }
        }
      },
    ) { p -> PrisonerRow(p, onClick = { onPrisoner(p.id) }) }
  }
}

@Composable
fun FacilitiesScreen(onBack: () -> Unit, onFacility: (Int) -> Unit, viewModel: FacilitiesViewModel = hiltViewModel()) {
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  DetailScaffold(title = "Facilities & mail rules", onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = "No facilities match.",
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, "Search facilities")
            ChipRow(routings, filter.routing, viewModel::setRouting, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf(true to "Relay available"), filter.relay, viewModel::setRelay, allLabel = "Any routing", modifier = Modifier.padding(top = 4.dp))
          }
        }
      },
    ) { f -> FacilityRow(f, onClick = { onFacility(f.id) }) }
  }
}

@Composable
fun GroupsScreen(onBack: () -> Unit, onGroup: (Int) -> Unit, viewModel: GroupsViewModel = hiltViewModel()) {
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  DetailScaffold(title = "Support groups", onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = "No groups match.",
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, "Search groups")
            ChipRow(networkRoles, filter.networkRole, viewModel::setNetworkRole, modifier = Modifier.padding(top = 8.dp))
            ChipRow(Services.labels.toList(), filter.service, viewModel::setService, allLabel = "Any service", modifier = Modifier.padding(top = 4.dp))
          }
        }
      },
    ) { g -> GroupRow(g, onClick = { onGroup(g.id) }) }
  }
}

@Composable
fun FacilityRow(f: Facility, onClick: () -> Unit) {
  RecordRow(
    title = f.name,
    secondary = f.shortLocation.takeIf { it.isNotBlank() },
    subtitle = f.routing.label + (if (f.relayGroups.isNotEmpty()) " · via " + f.relayGroups.joinToString { it.name } else ""),
    notice = if (f.verification.isStale()) "⚠ Not verified in over 6 months" else null,
    onClick = onClick,
  )
}

@Composable
fun GroupRow(g: Group, onClick: () -> Unit) {
  RecordRow(
    title = g.name,
    secondary = g.location.takeIf { it.isNotBlank() },
    subtitle = g.about?.let { if (it.length > 140) it.take(137) + "…" else it },
    tags = g.services.map { Services.label(it) }.take(4),
    onClick = onClick,
  )
}

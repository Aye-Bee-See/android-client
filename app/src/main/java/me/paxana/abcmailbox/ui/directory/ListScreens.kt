package me.paxana.abcmailbox.ui.directory

import me.paxana.abcmailbox.text.rememberStrings
import me.paxana.abcmailbox.R
import androidx.compose.ui.res.stringResource
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

private val prisonerStatuses = listOf("incarcerated" to R.string.prisoner_status_incarcerated, "pretrial" to R.string.prisoner_status_pretrial, "free" to R.string.prisoner_status_free)
private val routings = Routing.entries.filter { it != Routing.UNKNOWN }.map { it.key to it.labelRes }
private val networkRoles = listOf("collecting" to R.string.role_filter_collecting, "relay" to R.string.role_filter_relay)

/** Filter options are declared with resource ids (there is no composition up here) and named when drawn. */
@Composable
private fun <T> List<Pair<T, Int>>.named(): List<Pair<T, String>> = map { it.first to stringResource(it.second) }

@Composable
fun PrisonersScreen(
  onBack: () -> Unit,
  onPrisoner: (Int) -> Unit,
  title: String = stringResource(R.string.list_prisoners_title),
  viewModel: PrisonersViewModel = hiltViewModel(),
) {
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val items = viewModel.items.collectAsLazyPagingItems()
  DetailScaffold(title = title, onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = stringResource(R.string.list_prisoners_empty),
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, stringResource(R.string.search_by_name))
            ChipRow(prisonerStatuses.named(), filter.status, viewModel::setStatus, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf(true to stringResource(R.string.filter_featured)), filter.featured, viewModel::setFeatured, allLabel = stringResource(R.string.filter_everyone), modifier = Modifier.padding(top = 4.dp))
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
  DetailScaffold(title = stringResource(R.string.list_facilities_title), onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = stringResource(R.string.list_facilities_empty),
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, stringResource(R.string.search_facilities))
            ChipRow(routings.named(), filter.routing, viewModel::setRouting, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf(true to stringResource(R.string.filter_relay_available)), filter.relay, viewModel::setRelay, allLabel = stringResource(R.string.filter_any_routing), modifier = Modifier.padding(top = 4.dp))
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
  DetailScaffold(title = stringResource(R.string.list_groups_title), onBack = onBack) { padding ->
    PagedList(
      items = items,
      emptyText = stringResource(R.string.list_groups_empty),
      modifier = Modifier.fillMaxSize().padding(padding),
      header = {
        item("filters") {
          Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SearchField(filter.query, viewModel::setQuery, stringResource(R.string.search_groups))
            ChipRow(networkRoles.named(), filter.networkRole, viewModel::setNetworkRole, modifier = Modifier.padding(top = 8.dp))
            ChipRow(Services.keys.map { it to Services.label(it, rememberStrings()) }, filter.service, viewModel::setService, allLabel = stringResource(R.string.filter_any_service), modifier = Modifier.padding(top = 4.dp))
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
    subtitle = if (f.relayGroups.isNotEmpty()) stringResource(R.string.routing_via, stringResource(f.routing.labelRes), f.relayGroups.joinToString { it.name }) else stringResource(f.routing.labelRes),
    notice = if (f.verification.isStale()) stringResource(R.string.not_verified_6_months) else null,
    onClick = onClick,
  )
}

@Composable
fun GroupRow(g: Group, onClick: () -> Unit) {
  RecordRow(
    title = g.name,
    secondary = g.location.takeIf { it.isNotBlank() },
    subtitle = g.about?.let { if (it.length > 140) it.take(137) + "…" else it },
    tags = rememberStrings().let { st -> g.services.map { Services.label(it, st) }.take(4) },
    onClick = onClick,
  )
}

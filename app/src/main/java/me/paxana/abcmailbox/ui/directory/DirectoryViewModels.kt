package me.paxana.abcmailbox.ui.directory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.paxana.abcmailbox.data.api.ApiResult
import me.paxana.abcmailbox.data.api.AppError
import me.paxana.abcmailbox.data.repo.DirectoryRepository
import me.paxana.abcmailbox.data.repo.FacilityFilter
import me.paxana.abcmailbox.data.repo.GroupFilter
import me.paxana.abcmailbox.data.repo.PrisonerFilter
import me.paxana.abcmailbox.domain.Facility
import me.paxana.abcmailbox.domain.Group
import me.paxana.abcmailbox.domain.Prisoner
import me.paxana.abcmailbox.ui.nav.FacilityRoute
import me.paxana.abcmailbox.ui.nav.GroupRoute
import me.paxana.abcmailbox.ui.nav.PrisonerRoute
import javax.inject.Inject

/** Loading / failed / loaded for a single record. */
sealed interface Loadable<out T> {
  data object Loading : Loadable<Nothing>
  data class Failed(val error: AppError) : Loadable<Nothing>
  data class Loaded<T>(val value: T) : Loadable<T>
}

/**
 * The list ViewModels share one shape: a filter the screen edits, and a
 * paging flow that restarts whenever the filter changes. `debounce` waits for
 * typing to pause before hitting the network; `cachedIn` keeps the loaded
 * pages across rotation.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PrisonersViewModel @Inject constructor(repo: DirectoryRepository) : ViewModel() {
  private val _filter = MutableStateFlow(PrisonerFilter())
  val filter: StateFlow<PrisonerFilter> = _filter.asStateFlow()
  val items: Flow<PagingData<Prisoner>> = _filter.debounce(250).distinctUntilChanged()
    .flatMapLatest { repo.prisoners(it) }.cachedIn(viewModelScope)

  fun setQuery(q: String) = _filter.update { it.copy(query = q) }
  fun setStatus(s: String?) = _filter.update { it.copy(status = s) }
  fun setFeatured(f: Boolean?) = _filter.update { it.copy(featured = f) }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class FacilitiesViewModel @Inject constructor(repo: DirectoryRepository) : ViewModel() {
  private val _filter = MutableStateFlow(FacilityFilter())
  val filter: StateFlow<FacilityFilter> = _filter.asStateFlow()
  val items: Flow<PagingData<Facility>> = _filter.debounce(250).distinctUntilChanged()
    .flatMapLatest { repo.facilities(it) }.cachedIn(viewModelScope)

  fun setQuery(q: String) = _filter.update { it.copy(query = q) }
  fun setRouting(r: String?) = _filter.update { it.copy(routing = r) }
  fun setRelay(r: Boolean?) = _filter.update { it.copy(relay = r) }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class GroupsViewModel @Inject constructor(repo: DirectoryRepository) : ViewModel() {
  private val _filter = MutableStateFlow(GroupFilter())
  val filter: StateFlow<GroupFilter> = _filter.asStateFlow()
  val items: Flow<PagingData<Group>> = _filter.debounce(250).distinctUntilChanged()
    .flatMapLatest { repo.groups(it) }.cachedIn(viewModelScope)

  fun setQuery(q: String) = _filter.update { it.copy(query = q) }
  fun setNetworkRole(r: String?) = _filter.update { it.copy(networkRole = r) }
  fun setService(s: String?) = _filter.update { it.copy(service = s) }
}

@HiltViewModel
class DirectoryHomeViewModel @Inject constructor(private val repo: DirectoryRepository) : ViewModel() {
  private val _featured = MutableStateFlow<Loadable<List<Prisoner>>>(Loadable.Loading)
  val featured: StateFlow<Loadable<List<Prisoner>>> = _featured.asStateFlow()

  init { load() }

  fun load() {
    _featured.value = Loadable.Loading
    viewModelScope.launch {
      _featured.value = when (val r = repo.featuredPrisoners()) {
        is ApiResult.Success -> Loadable.Loaded(r.value)
        is ApiResult.Failure -> Loadable.Failed(r.error)
      }
    }
  }
}

/**
 * The prisoner page needs two reads: the prisoner (with its facility embedded)
 * and the facility itself, because only the facility read embeds mail rules
 * and relay groups. The second is best-effort; the page renders without it.
 */
@HiltViewModel
class PrisonerViewModel @Inject constructor(
  private val repo: DirectoryRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {
  private val route = savedStateHandle.toRoute<PrisonerRoute>()

  private val _prisoner = MutableStateFlow<Loadable<Prisoner>>(Loadable.Loading)
  val prisoner: StateFlow<Loadable<Prisoner>> = _prisoner.asStateFlow()

  private val _facility = MutableStateFlow<Facility?>(null)
  val facility: StateFlow<Facility?> = _facility.asStateFlow()

  init { load() }

  fun load() {
    _prisoner.value = Loadable.Loading
    viewModelScope.launch {
      when (val r = repo.prisoner(route.id)) {
        is ApiResult.Failure -> _prisoner.value = Loadable.Failed(r.error)
        is ApiResult.Success -> {
          _prisoner.value = Loadable.Loaded(r.value)
          r.value.facilityId?.let { id ->
            (repo.facility(id) as? ApiResult.Success)?.let { _facility.value = it.value }
          }
        }
      }
    }
  }
}

@HiltViewModel
class FacilityViewModel @Inject constructor(
  private val repo: DirectoryRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {
  private val route = savedStateHandle.toRoute<FacilityRoute>()
  private val _facility = MutableStateFlow<Loadable<Facility>>(Loadable.Loading)
  val facility: StateFlow<Loadable<Facility>> = _facility.asStateFlow()

  init { load() }

  fun load() {
    _facility.value = Loadable.Loading
    viewModelScope.launch {
      _facility.value = when (val r = repo.facility(route.id)) {
        is ApiResult.Success -> Loadable.Loaded(r.value)
        is ApiResult.Failure -> Loadable.Failed(r.error)
      }
    }
  }
}

@HiltViewModel
class GroupViewModel @Inject constructor(
  private val repo: DirectoryRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {
  private val route = savedStateHandle.toRoute<GroupRoute>()
  private val _group = MutableStateFlow<Loadable<Group>>(Loadable.Loading)
  val group: StateFlow<Loadable<Group>> = _group.asStateFlow()

  init { load() }

  fun load() {
    _group.value = Loadable.Loading
    viewModelScope.launch {
      _group.value = when (val r = repo.group(route.id)) {
        is ApiResult.Success -> Loadable.Loaded(r.value)
        is ApiResult.Failure -> Loadable.Failed(r.error)
      }
    }
  }
}

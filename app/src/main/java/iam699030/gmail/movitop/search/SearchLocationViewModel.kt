package iam699030.gmail.movitop.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeocodePlace
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.PinnedPlacesRepository
import iam699030.gmail.movitop.data.PinnedSlot
import iam699030.gmail.movitop.data.PlaceSuggestions
import iam699030.gmail.movitop.data.RealMotisRepository
import iam699030.gmail.movitop.data.RecentPlacesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchLocationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: MotisRepository = RealMotisRepository(application)
    private val recentPlaces = RecentPlacesRepository(application)
    private val pinnedPlaces = PinnedPlacesRepository(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<GeocodePlace>>(emptyList())
    val results: StateFlow<List<GeocodePlace>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _recents = MutableStateFlow(recentPlaces.getAll())
    /** Recently-picked places, shown before the user types anything. */
    val recents: StateFlow<List<GeocodePlace>> = _recents.asStateFlow()

    private val _homePlace = MutableStateFlow(pinnedPlaces.get(PinnedSlot.HOME))
    val homePlace: StateFlow<GeocodePlace?> = _homePlace.asStateFlow()

    private val _workPlace = MutableStateFlow(pinnedPlaces.get(PinnedSlot.WORK))
    val workPlace: StateFlow<GeocodePlace?> = _workPlace.asStateFlow()

    /** Which slot the *next* picked place should be saved into, if any — see [SearchLocationActivity]. */
    var pendingPinnedSlot: PinnedSlot? = null

    fun pinPlace(slot: PinnedSlot, place: GeocodePlace) {
        pinnedPlaces.set(slot, place)
        when (slot) {
            PinnedSlot.HOME -> _homePlace.value = place
            PinnedSlot.WORK -> _workPlace.value = place
        }
    }

    init {
        _query
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { text -> fetchPlaces(text) }
            .launchIn(viewModelScope)
    }

    /** Records a picked place for next time — skips the "current location" sentinel, which is stale by then. */
    fun recordPick(place: GeocodePlace) {
        if (place.id == CURRENT_LOCATION_ID) return
        recentPlaces.record(place)
        _recents.value = recentPlaces.getAll()
    }

    fun setQuery(text: String) {
        _query.update { text }
    }

    fun setInitialQuery(text: String) {
        val trimmed = text.trim()
        _query.value = trimmed
        if (trimmed.length >= 2) {
            viewModelScope.launch { fetchPlaces(trimmed) }
        }
    }

    private suspend fun fetchPlaces(text: String) {
        val trimmed = text.trim()
        if (trimmed.length < 2) {
            _results.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        val israelLabel = getApplication<Application>().getString(R.string.country_israel)
        _results.value = PlaceSuggestions.localMatches(trimmed).map { name ->
            GeocodePlace(name = name, subtitle = israelLabel, lat = 0.0, lon = 0.0)
        }
        try {
            _results.value = repository.geocodePlaces(trimmed)
        } catch (_: Exception) {
            // Keep local matches on network failure.
        } finally {
            _isSearching.value = false
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        const val CURRENT_LOCATION_ID = "current_location"
    }
}

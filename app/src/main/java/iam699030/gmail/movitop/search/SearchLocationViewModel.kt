package iam699030.gmail.movitop.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeocodePlace
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.PlaceSuggestions
import iam699030.gmail.movitop.data.RealMotisRepository
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

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<GeocodePlace>>(emptyList())
    val results: StateFlow<List<GeocodePlace>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        _query
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { text -> fetchPlaces(text) }
            .launchIn(viewModelScope)
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
    }
}

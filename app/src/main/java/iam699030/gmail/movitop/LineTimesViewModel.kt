package iam699030.gmail.movitop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.data.FavoriteLinesRepository
import iam699030.gmail.movitop.data.LineDeparture
import iam699030.gmail.movitop.data.LineScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class LineTimesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LineScheduleRepository(application)
    private val favorites = FavoriteLinesRepository(application)

    data class UiState(
        val query: String = "",
        val dateEpochMillis: Long = todayMidnight(),
        val results: List<LineDeparture> = emptyList(),
        val searched: Boolean = false,
        val isAvailable: Boolean = true,
        // True when [results] are the saved lines' departures shown because the
        // query is blank, rather than an actual search result.
        val showingFavorites: Boolean = false,
        val favoriteLines: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(
        UiState(isAvailable = repository.isAvailable(), favoriteLines = favorites.getAll())
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadFavoritesIfBlank()
    }

    fun setDate(epochMillis: Long) {
        _uiState.value = _uiState.value.copy(dateEpochMillis = epochMillis)
        if (_uiState.value.query.isBlank()) loadFavoritesIfBlank() else search(_uiState.value.query)
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.isBlank()) {
            loadFavoritesIfBlank()
            return
        }
        viewModelScope.launch {
            val date = _uiState.value.dateEpochMillis
            val results = withContext(Dispatchers.IO) {
                repository.findDepartures(query.trim(), date)
            }
            _uiState.value = _uiState.value.copy(results = results, searched = true, showingFavorites = false)
        }
    }

    fun toggleFavorite(routeShortName: String) {
        favorites.toggle(routeShortName)
        _uiState.value = _uiState.value.copy(favoriteLines = favorites.getAll())
        if (_uiState.value.query.isBlank()) loadFavoritesIfBlank()
    }

    /** With no active query, show today's departures for every saved line instead of nothing. */
    private fun loadFavoritesIfBlank() {
        val saved = favorites.getAll()
        if (saved.isEmpty()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), searched = false, showingFavorites = false)
            return
        }
        viewModelScope.launch {
            val date = _uiState.value.dateEpochMillis
            val results = withContext(Dispatchers.IO) {
                saved.flatMap { line -> repository.findDepartures(line, date) }
                    .sortedBy { it.departureTime }
            }
            _uiState.value = _uiState.value.copy(results = results, searched = true, showingFavorites = true)
        }
    }

    companion object {
        private fun todayMidnight(): Long {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}

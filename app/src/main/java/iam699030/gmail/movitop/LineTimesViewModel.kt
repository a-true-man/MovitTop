package iam699030.gmail.movitop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.data.FavoriteLinesRepository
import iam699030.gmail.movitop.data.LineDeparture
import iam699030.gmail.movitop.data.LineScheduleRepository
import iam699030.gmail.movitop.data.LineSummary
import iam699030.gmail.movitop.data.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Which of the three drilled-down screens is showing — see [LineTimesViewModel.UiState.screen]. */
sealed interface LineTimesScreen {
    /** Query box + the list of distinct lines found (or saved lines, if the query is blank). */
    data object Search : LineTimesScreen

    /** One line's timetable for the chosen day, with a direction toggle when [line].pairedRouteId != null. */
    data class LineDetail(val line: LineSummary) : LineTimesScreen

    /** One specific trip's full stop-by-stop arrival list. */
    data class TripDetail(val line: LineSummary, val departure: LineDeparture) : LineTimesScreen
}

@OptIn(FlowPreview::class)
class LineTimesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LineScheduleRepository(application)
    private val favorites = FavoriteLinesRepository(application)

    data class UiState(
        val query: String = "",
        val dateEpochMillis: Long = todayMidnight(),
        val screen: LineTimesScreen = LineTimesScreen.Search,
        // Search screen
        val lineSummaries: List<LineSummary> = emptyList(),
        val searched: Boolean = false,
        val isAvailable: Boolean = true,
        // True when [lineSummaries] are the saved lines shown because the
        // query is blank, rather than an actual search result.
        val showingFavorites: Boolean = false,
        val favoriteLineIds: List<String> = emptyList(),
        // Line detail screen
        val lineDepartures: List<LineDeparture> = emptyList(),
        // Trip detail screen
        val tripStops: List<TripStop> = emptyList()
    )

    private val _uiState = MutableStateFlow(
        UiState(isAvailable = repository.isAvailable(), favoriteLineIds = favorites.getAll())
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Each keystroke (and toggling a favorite while the query is blank) starts
    // a new lookup; without cancelling the previous one, two in flight at once
    // could finish out of order and leave showingFavorites/results mismatched
    // with whatever the box actually shows.
    private var loadJob: Job? = null

    // findLineSummaries() opens and closes the on-device SQLite file on every
    // call (see LineScheduleRepository) — searching on every raw keystroke
    // meant a full DB round trip per character typed. Debounced here the same
    // way SearchLocationViewModel debounces its own remote geocode calls.
    private val _queryChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        loadFavoritesIfBlank()
        _queryChanges
            .debounce(DEBOUNCE_MS)
            .onEach { runSearch(it) }
            .launchIn(viewModelScope)
    }

    fun setDate(epochMillis: Long) {
        _uiState.value = _uiState.value.copy(dateEpochMillis = epochMillis)
        // A date change is a single deliberate action, not rapid typing —
        // re-run immediately rather than routing it through the debounce.
        val line = (_uiState.value.screen as? LineTimesScreen.LineDetail)?.line ?: return
        loadLineDetail(line)
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.isBlank()) {
            loadJob?.cancel()
            loadFavoritesIfBlank()
            return
        }
        _queryChanges.tryEmit(query)
    }

    private fun runSearch(query: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                repository.findLineSummaries(query.trim())
            }
            _uiState.value = _uiState.value.copy(
                lineSummaries = results, searched = true, showingFavorites = false
            )
        }
    }

    /**
     * Favorites are keyed by [LineSummary.routeId] — the merged line's own
     * stable identity — not its bare route number, which several unrelated
     * lines can share (see build_line_schedules.py's line_group_id).
     */
    fun toggleFavorite(line: LineSummary) {
        favorites.toggle(line.routeId)
        _uiState.value = _uiState.value.copy(favoriteLineIds = favorites.getAll())
        if (_uiState.value.query.isBlank()) loadFavoritesIfBlank()
    }

    /** With no active query, show every saved line instead of nothing. */
    private fun loadFavoritesIfBlank() {
        loadJob?.cancel()
        val saved = favorites.getAll()
        if (saved.isEmpty()) {
            _uiState.value = _uiState.value.copy(lineSummaries = emptyList(), searched = false, showingFavorites = false)
            return
        }
        loadJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                saved.mapNotNull { routeId -> repository.findRouteInfo(routeId) }
            }
            _uiState.value = _uiState.value.copy(lineSummaries = results, searched = true, showingFavorites = true)
        }
    }

    /** Drills into one line's timetable for the current day. */
    fun selectLine(line: LineSummary) {
        _uiState.value = _uiState.value.copy(screen = LineTimesScreen.LineDetail(line))
        loadLineDetail(line)
    }

    /** Flips to the reverse direction of the line currently shown, if one exists. */
    fun toggleDirection() {
        val current = (_uiState.value.screen as? LineTimesScreen.LineDetail)?.line ?: return
        val pairedRouteId = current.pairedRouteId ?: return
        viewModelScope.launch {
            val paired = withContext(Dispatchers.IO) { repository.findRouteInfo(pairedRouteId) } ?: return@launch
            _uiState.value = _uiState.value.copy(screen = LineTimesScreen.LineDetail(paired))
            loadLineDetail(paired)
        }
    }

    private fun loadLineDetail(line: LineSummary) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val date = _uiState.value.dateEpochMillis
            val results = withContext(Dispatchers.IO) {
                repository.findDeparturesForRoute(line.routeId, date)
            }
            _uiState.value = _uiState.value.copy(lineDepartures = results)
        }
    }

    /** Drills into one specific trip's full stop-by-stop arrival list. */
    fun selectTrip(departure: LineDeparture) {
        val line = (_uiState.value.screen as? LineTimesScreen.LineDetail)?.line ?: return
        _uiState.value = _uiState.value.copy(screen = LineTimesScreen.TripDetail(line, departure), tripStops = emptyList())
        viewModelScope.launch {
            val stops = withContext(Dispatchers.IO) { repository.findTripStops(departure.tripId) }
            _uiState.value = _uiState.value.copy(tripStops = stops)
        }
    }

    /** Pops one level back; returns false once already at [LineTimesScreen.Search] (the activity should finish instead). */
    fun goBack(): Boolean {
        val screen = _uiState.value.screen
        _uiState.value = _uiState.value.copy(
            screen = when (screen) {
                is LineTimesScreen.Search -> return false
                is LineTimesScreen.LineDetail -> LineTimesScreen.Search
                is LineTimesScreen.TripDetail -> LineTimesScreen.LineDetail(screen.line)
            }
        )
        return true
    }

    companion object {
        private const val DEBOUNCE_MS = 250L

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

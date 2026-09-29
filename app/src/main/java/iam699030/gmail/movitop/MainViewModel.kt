package iam699030.gmail.movitop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.PlaceGeocoder
import iam699030.gmail.movitop.data.RealMotisRepository
import iam699030.gmail.movitop.data.RouteDetail
import iam699030.gmail.movitop.data.RouteOption
import iam699030.gmail.movitop.data.TripTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: MotisRepository = RealMotisRepository(application)

    sealed interface RoutingState {
        data object Idle : RoutingState
        data object Calculating : RoutingState
        data object ResultsReady : RoutingState
        data class ViewingRouteDetails(val selectedRoute: RouteOption) : RoutingState
    }

    data class UiState(
        val originQuery: String = "",
        val destinationQuery: String = "",
        // Set when the query came from an exact pick (search result or GPS fix)
        // rather than free text, so search()/selectRoute() can route against
        // that exact point instead of re-geocoding the name (see MotisRepository).
        val originCoord: GeoPoint? = null,
        val destinationCoord: GeoPoint? = null,
        val routingState: RoutingState = RoutingState.Idle,
        val errorMessage: String? = null,
        val tripTime: TripTime = TripTime.NOW
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _routes = MutableStateFlow<List<RouteOption>?>(null)
    val routes: StateFlow<List<RouteOption>?> = _routes.asStateFlow()

    private val _routeDetail = MutableStateFlow<RouteDetail?>(null)
    val routeDetail: StateFlow<RouteDetail?> = _routeDetail.asStateFlow()

    fun setOrigin(name: String, coord: GeoPoint? = null) {
        _uiState.update { it.copy(originQuery = name.trim(), originCoord = coord, errorMessage = null) }
    }

    fun setDestination(name: String, coord: GeoPoint? = null) {
        _uiState.update { it.copy(destinationQuery = name.trim(), destinationCoord = coord, errorMessage = null) }
    }

    fun setTripTime(tripTime: TripTime) {
        _uiState.update { it.copy(tripTime = tripTime) }
    }

    fun search() {
        val origin = _uiState.value.originQuery.trim()
        val destination = _uiState.value.destinationQuery.trim()
        if (origin.isEmpty() || destination.isEmpty()) return

        // Without this check, a fresh install with no imported graph fails
        // every search against a routing engine that can never come up, and
        // reports it as the same generic "network error" as an actual outage
        // — nothing tells the user they need to import data at all.
        if (!MotisForegroundService.hasOfflineData(getApplication())) {
            _uiState.update {
                it.copy(
                    originQuery = origin,
                    destinationQuery = destination,
                    routingState = RoutingState.Idle,
                    errorMessage = getString(R.string.search_error_no_data)
                )
            }
            return
        }

        _routes.value = null
        _routeDetail.value = null
        _uiState.update {
            it.copy(
                originQuery = origin,
                destinationQuery = destination,
                routingState = RoutingState.Calculating,
                errorMessage = null
            )
        }

        val originCoord = _uiState.value.originCoord
        val destCoord = _uiState.value.destinationCoord
        viewModelScope.launch {
            try {
                val result = repository.getRoutes(
                    origin, destination, _uiState.value.tripTime, originCoord, destCoord
                )
                if (result.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            routingState = RoutingState.Idle,
                            errorMessage = getString(R.string.search_no_routes)
                        )
                    }
                    return@launch
                }
                _routes.value = result
                _uiState.update { it.copy(routingState = RoutingState.ResultsReady) }
            } catch (e: PlaceGeocoder.PlaceNotFoundException) {
                val place = e.message?.substringAfter("\"")?.substringBefore("\"") ?: destination
                _uiState.update {
                    it.copy(
                        routingState = RoutingState.Idle,
                        errorMessage = getString(R.string.search_error_place, place)
                    )
                }
            } catch (e: Exception) {
                val message = if (isNetworkError(e)) {
                    getString(R.string.search_error_network)
                } else {
                    getString(R.string.search_error_place, destination)
                }
                _uiState.update {
                    it.copy(routingState = RoutingState.Idle, errorMessage = message)
                }
            }
        }
    }

    fun selectRoute(option: RouteOption) {
        // Allowed from ResultsReady (first pick) and from ViewingRouteDetails
        // (switching to a different option without leaving the detail pane
        // or re-running search()) — not from Idle/Calculating.
        val state = _uiState.value.routingState
        if (state != RoutingState.ResultsReady && state !is RoutingState.ViewingRouteDetails) return
        val origin = _uiState.value.originQuery
        val destination = _uiState.value.destinationQuery
        _routeDetail.value = null
        _uiState.update { it.copy(routingState = RoutingState.ViewingRouteDetails(option)) }

        val originCoord = _uiState.value.originCoord
        val destCoord = _uiState.value.destinationCoord
        viewModelScope.launch {
            try {
                _routeDetail.value = repository.getRouteDetail(
                    origin, destination, option, _uiState.value.tripTime, originCoord, destCoord
                )
            } catch (e: PlaceGeocoder.PlaceNotFoundException) {
                val place = e.message?.substringAfter("\"")?.substringBefore("\"") ?: destination
                _uiState.update {
                    it.copy(errorMessage = getString(R.string.search_error_place, place))
                }
            } catch (e: Exception) {
                val message = if (isNetworkError(e)) {
                    getString(R.string.search_error_network)
                } else {
                    getString(R.string.search_error_place, destination)
                }
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }

    fun backToSummary() {
        if (_uiState.value.routingState is RoutingState.ViewingRouteDetails) {
            _routeDetail.value = null
            _uiState.update { it.copy(routingState = RoutingState.ResultsReady) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun isNetworkError(e: Throwable): Boolean = when (e) {
        is IOException, is UnknownHostException, is SocketTimeoutException -> true
        else -> e.cause?.let(::isNetworkError) == true
    }

    private fun getString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}

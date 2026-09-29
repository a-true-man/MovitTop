package iam699030.gmail.movitop.nearby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import iam699030.gmail.movitop.MotisForegroundService
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.NearbyDeparture
import iam699030.gmail.movitop.data.RealMotisRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NearbyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MotisRepository = RealMotisRepository(application)

    sealed interface LoadState {
        data object Locating : LoadState
        data object LocationUnavailable : LoadState
        // Distinct from an empty Loaded() result — an empty list there
        // genuinely means "no stops nearby", which looked identical to
        // "the offline graph was never installed" before this existed.
        data object NoOfflineData : LoadState
        data class Loaded(val departures: List<NearbyDeparture>) : LoadState
    }

    private val _state = MutableStateFlow<LoadState>(LoadState.Locating)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    fun load(point: GeoPoint) {
        if (!MotisForegroundService.hasOfflineData(getApplication())) {
            _state.value = LoadState.NoOfflineData
            return
        }
        _state.value = LoadState.Locating
        viewModelScope.launch {
            val results = repository.nearbyDepartures(point).sortedBy { it.departEpochMillis }
            _state.update { LoadState.Loaded(results) }
        }
    }

    fun locationUnavailable() {
        _state.value = LoadState.LocationUnavailable
    }
}

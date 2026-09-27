package iam699030.gmail.movitop.nearby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        data class Loaded(val departures: List<NearbyDeparture>) : LoadState
    }

    private val _state = MutableStateFlow<LoadState>(LoadState.Locating)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    fun load(point: GeoPoint) {
        _state.value = LoadState.Locating
        viewModelScope.launch {
            val results = repository.nearbyDepartures(point).sortedBy { it.minutesUntil }
            _state.update { LoadState.Loaded(results) }
        }
    }

    fun locationUnavailable() {
        _state.value = LoadState.LocationUnavailable
    }
}

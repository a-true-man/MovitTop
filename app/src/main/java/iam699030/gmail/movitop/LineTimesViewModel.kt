package iam699030.gmail.movitop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    data class UiState(
        val query: String = "",
        val dateEpochMillis: Long = todayMidnight(),
        val results: List<LineDeparture> = emptyList(),
        val searched: Boolean = false,
        val isAvailable: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState(isAvailable = repository.isAvailable()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun setDate(epochMillis: Long) {
        _uiState.value = _uiState.value.copy(dateEpochMillis = epochMillis)
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), searched = false)
            return
        }
        viewModelScope.launch {
            val date = _uiState.value.dateEpochMillis
            val results = withContext(Dispatchers.IO) {
                repository.findDepartures(query.trim(), date)
            }
            _uiState.value = _uiState.value.copy(results = results, searched = true)
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

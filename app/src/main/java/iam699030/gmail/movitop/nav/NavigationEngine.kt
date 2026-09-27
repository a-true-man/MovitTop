package iam699030.gmail.movitop.nav

import iam699030.gmail.movitop.data.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class NavState(
    val stepIndex: Int,
    val step: NavigationStep?,
    val distanceToTargetMeters: Double? = null,
    val usingGps: Boolean = false,
    val finished: Boolean = false
)

/**
 * Drives progress through a [NavigationStep] list. Hybrid by design (per
 * product decision): when a recent GPS fix is close enough to the current
 * step's target, it advances immediately; otherwise a per-step schedule
 * countdown (walking-speed / leg duration estimates) advances it instead, so
 * navigation still works with GPS off or unsupported. A manual next/back is
 * always available regardless of mode.
 */
class NavigationEngine(
    private val steps: List<NavigationStep>,
    private val locationTracker: LocationTracker?
) {
    private val _state = MutableStateFlow(
        NavState(stepIndex = 0, step = steps.firstOrNull(), finished = steps.isEmpty())
    )
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val _stepChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once per step transition — the UI vibrates on this (no TTS yet). */
    val stepChanged: SharedFlow<Unit> = _stepChanged

    private var scheduleJob: Job? = null
    private var lastGpsPoint: GeoPoint? = null
    private var navScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        navScope = scope
        locationTracker?.let { tracker ->
            scope.launch {
                tracker.updates().collectLatest { point ->
                    lastGpsPoint = point
                    onGpsPoint(point)
                }
            }
        }
        runScheduleForCurrentStep()
    }

    fun manualAdvance() {
        val current = _state.value
        if (current.finished) return
        moveTo(current.stepIndex + 1)
    }

    fun manualBack() {
        val current = _state.value
        if (current.stepIndex == 0) return
        moveTo(current.stepIndex - 1)
    }

    private fun onGpsPoint(point: GeoPoint) {
        val current = _state.value
        val step = current.step ?: return
        val distance = haversineMeters(point, step.point)
        _state.value = current.copy(distanceToTargetMeters = distance, usingGps = true)

        if (step.advanceMode() == AdvanceMode.GPS && distance <= thresholdFor(step)) {
            moveTo(current.stepIndex + 1)
        }
    }

    private fun runScheduleForCurrentStep() {
        scheduleJob?.cancel()
        val scope = navScope ?: return
        val step = _state.value.step ?: return
        if (step.advanceMode() != AdvanceMode.GPS || step.estimatedSeconds <= 0) return

        scheduleJob = scope.launch {
            delay(step.estimatedSeconds * 1000L)
            // Only fire if GPS hasn't already moved us on and we're still on this step.
            if (_state.value.step === step) {
                moveTo(_state.value.stepIndex + 1)
            }
        }
    }

    private fun moveTo(index: Int) {
        scheduleJob?.cancel()
        if (index >= steps.size) {
            _state.value = _state.value.copy(finished = true, step = null)
            return
        }
        _state.value = NavState(
            stepIndex = index,
            step = steps[index],
            usingGps = lastGpsPoint != null
        )
        _stepChanged.tryEmit(Unit)
        runScheduleForCurrentStep()
    }

    private fun thresholdFor(step: NavigationStep): Double = when (step) {
        is NavigationStep.Walk -> 20.0
        else -> 35.0
    }

    /** Call the returned job's scope's cancellation (e.g. activity onDestroy) to stop tracking. */
    fun stop() {
        scheduleJob?.cancel()
    }
}

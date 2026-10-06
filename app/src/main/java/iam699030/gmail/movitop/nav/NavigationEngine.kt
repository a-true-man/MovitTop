package iam699030.gmail.movitop.nav

import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.ReminderSettings
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
    val finished: Boolean = false,
    /** Stops left until the alight stop on the current [NavigationStep.Ride], once GPS has matched at least one. */
    val ridingStopsRemaining: Int? = null,
    /** Last known GPS fix, carried across step transitions — the live-nav map's position marker. */
    val currentPosition: GeoPoint? = null
)

data class ReminderEvent(val stopsRemaining: Int, val alightStopName: String)

/**
 * Drives progress through a [NavigationStep] list. Hybrid by design (per
 * product decision): when a recent GPS fix is close enough to the current
 * step's target, it advances immediately; otherwise a per-step schedule
 * countdown (walking-speed / leg duration estimates) advances it instead, so
 * navigation still works with GPS off or unsupported. A manual next/back is
 * always available regardless of mode.
 */
class NavigationEngine(
    val steps: List<NavigationStep>,
    private val locationTracker: LocationTracker?,
    private var reminderSettings: ReminderSettings = ReminderSettings()
) {
    private val _state = MutableStateFlow(
        NavState(stepIndex = 0, step = steps.firstOrNull(), finished = steps.isEmpty())
    )
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val _stepChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once per step transition — the UI vibrates on this (no TTS yet). */
    val stepChanged: SharedFlow<Unit> = _stepChanged

    private val _reminderFired = MutableSharedFlow<ReminderEvent>(extraBufferCapacity = 1)
    /** Emits once when the configured get-off reminder threshold is crossed on a Ride step. */
    val reminderFired: SharedFlow<ReminderEvent> = _reminderFired

    private var scheduleJob: Job? = null
    private var lastGpsPoint: GeoPoint? = null
    private var navScope: CoroutineScope? = null
    private var lastMatchedStopIndex = -1
    private var reminderFiredForStep = false

    /** Pushed live from the in-screen reminder settings dialog. */
    fun updateReminderSettings(settings: ReminderSettings) {
        reminderSettings = settings
    }

    fun start(scope: CoroutineScope) {
        navScope = scope
        runScheduleForCurrentStep()
    }

    /**
     * Collects GPS fixes until the calling coroutine is cancelled. Call this
     * from a lifecycle-gated scope (e.g. inside `repeatOnLifecycle(STARTED)`)
     * so location polling actually pauses while the screen/app is
     * backgrounded, unlike the schedule countdown in [start] — that one
     * deliberately keeps running on the raw scope, since it represents real
     * elapsed time that shouldn't reset just because the user isn't looking.
     */
    suspend fun trackGps() {
        val tracker = locationTracker ?: return
        tracker.updates().collectLatest { point ->
            lastGpsPoint = point
            onGpsPoint(point)
        }
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

    /** Jumps straight to [index] — used by the step-overview screen's "tap to jump" action. */
    fun jumpTo(index: Int) {
        if (index < 0 || index >= steps.size) return
        moveTo(index)
    }

    private fun onGpsPoint(point: GeoPoint) {
        val current = _state.value
        val step = current.step ?: return
        val distance = haversineMeters(point, step.point)
        _state.value = current.copy(distanceToTargetMeters = distance, usingGps = true, currentPosition = point)

        if (step is NavigationStep.Ride && step.stops.isNotEmpty()) {
            trackRideProgress(point, step)
        }

        if (step.advanceMode() == AdvanceMode.GPS && distance <= thresholdFor(step)) {
            moveTo(current.stepIndex + 1)
        }
    }

    /** Advances [lastMatchedStopIndex] as GPS passes each of [step]'s stops, and fires the get-off reminder once. */
    private fun trackRideProgress(point: GeoPoint, step: NavigationStep.Ride) {
        val nextIndex = lastMatchedStopIndex + 1
        if (nextIndex < step.stops.size && haversineMeters(point, step.stops[nextIndex].point) <= RIDE_STOP_PROXIMITY_METERS) {
            lastMatchedStopIndex = nextIndex
        }
        val remaining = (step.stops.size - 1 - lastMatchedStopIndex).coerceAtLeast(0)
        _state.value = _state.value.copy(ridingStopsRemaining = remaining)
        if (reminderSettings.enabled && !reminderFiredForStep && remaining == reminderSettings.stopsBefore) {
            reminderFiredForStep = true
            _reminderFired.tryEmit(ReminderEvent(remaining, step.alightStopName))
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
        lastMatchedStopIndex = -1
        reminderFiredForStep = false
        if (index >= steps.size) {
            _state.value = _state.value.copy(finished = true, step = null)
            return
        }
        _state.value = NavState(
            stepIndex = index,
            step = steps[index],
            usingGps = lastGpsPoint != null,
            currentPosition = lastGpsPoint
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

    private companion object {
        /** Reuses [thresholdFor]'s non-walk GPS-proximity threshold for matching a ride stop. */
        const val RIDE_STOP_PROXIMITY_METERS = 35.0
    }
}

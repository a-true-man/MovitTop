package iam699030.gmail.movitop.nav

import iam699030.gmail.movitop.data.GeoPoint

/**
 * One card the live-navigation screen shows the user, in order. Each carries
 * the point GPS proximity is checked against and a schedule-based fallback
 * duration, so navigation still progresses on devices with no/disabled GPS —
 * see [NavigationEngine].
 */
/** One stop on a [NavigationStep.Ride] leg — every intermediate stop plus the final alight stop, in order. */
data class RideStop(
    val name: String,
    val point: GeoPoint,
    val arriveTimeText: String? = null
)

sealed interface NavigationStep {
    val point: GeoPoint
    val estimatedSeconds: Long

    /** A turn-by-turn walking instruction (from MOTIS/OSR's step data). */
    data class Walk(
        val instruction: String,
        val distanceMeters: Double,
        override val point: GeoPoint,
        override val estimatedSeconds: Long,
        /** The full decoded path of this leg, for drawing it on the live-nav map. */
        val legPoints: List<GeoPoint> = emptyList()
    ) : NavigationStep

    /** Requires the user to confirm boarding — we have no live vehicle data to detect it. */
    data class Board(
        val stopName: String,
        val routeLabel: String,
        val agencyName: String?,
        override val point: GeoPoint,
        val departTimeText: String? = null,
        val colorArgb: Int = 0
    ) : NavigationStep {
        override val estimatedSeconds: Long = 0L
    }

    /** Riding a transit vehicle; advances by schedule countdown or GPS reaching the alight stop. */
    data class Ride(
        val routeLabel: String,
        val alightStopName: String,
        override val point: GeoPoint,
        override val estimatedSeconds: Long,
        val arriveTimeText: String? = null,
        val colorArgb: Int = 0,
        /** The full decoded path of this leg, for drawing it on the live-nav map. */
        val legPoints: List<GeoPoint> = emptyList(),
        /** Every intermediate stop plus the final alight stop, in ride order. */
        val stops: List<RideStop> = emptyList()
    ) : NavigationStep

    data class Alight(
        val stopName: String,
        override val point: GeoPoint
    ) : NavigationStep {
        override val estimatedSeconds: Long = 0L
    }

    data class Arrive(
        val placeName: String,
        override val point: GeoPoint,
        val arriveTimeText: String? = null
    ) : NavigationStep {
        override val estimatedSeconds: Long = 0L
    }
}

/** How the current step is expected to advance, shown to the user as a small badge. */
enum class AdvanceMode { GPS, SCHEDULE, MANUAL_ONLY }

fun NavigationStep.advanceMode(): AdvanceMode = when (this) {
    is NavigationStep.Board -> AdvanceMode.MANUAL_ONLY
    is NavigationStep.Alight, is NavigationStep.Arrive -> AdvanceMode.GPS
    is NavigationStep.Walk, is NavigationStep.Ride -> AdvanceMode.GPS
}

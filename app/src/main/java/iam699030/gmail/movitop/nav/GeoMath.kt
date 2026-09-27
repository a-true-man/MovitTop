package iam699030.gmail.movitop.nav

import iam699030.gmail.movitop.data.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in meters. */
fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * The point at [fraction] (0..1) of the way along [points] by cumulative
 * distance. Used to estimate where a turn-by-turn instruction sits along a
 * leg's polyline, since MOTIS's step data gives distances but not per-step
 * coordinates.
 */
fun pointAtFraction(points: List<GeoPoint>, fraction: Double): GeoPoint {
    if (points.isEmpty()) return GeoPoint(0.0, 0.0)
    if (points.size == 1 || fraction <= 0.0) return points.first()
    if (fraction >= 1.0) return points.last()

    val segmentLengths = points.zipWithNext { a, b -> haversineMeters(a, b) }
    val total = segmentLengths.sum()
    if (total <= 0.0) return points.first()

    val target = total * fraction
    var covered = 0.0
    for (i in segmentLengths.indices) {
        val segLen = segmentLengths[i]
        if (covered + segLen >= target || i == segmentLengths.lastIndex) {
            val into = if (segLen > 0) (target - covered) / segLen else 0.0
            val a = points[i]
            val b = points[i + 1]
            return GeoPoint(
                lat = a.lat + (b.lat - a.lat) * into,
                lon = a.lon + (b.lon - a.lon) * into
            )
        }
        covered += segLen
    }
    return points.last()
}

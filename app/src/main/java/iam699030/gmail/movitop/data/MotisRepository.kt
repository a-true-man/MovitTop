package iam699030.gmail.movitop.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import iam699030.gmail.movitop.R
import kotlinx.coroutines.delay

/** The five transport modes Movitop supports. */
enum class TransportMode(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int
) {
    TRANSIT(R.string.mode_transit, R.drawable.ic_mode_transit),
    DRIVER(R.string.mode_driver, R.drawable.ic_mode_driver),
    BIKE(R.string.mode_bike, R.drawable.ic_mode_bike),
    WALKING(R.string.mode_walking, R.drawable.ic_mode_walk),
    TAXI(R.string.mode_taxi, R.drawable.ic_mode_taxi)
}

/**
 * A single route summary card.
 *
 * @param durationMinutes trip duration in minutes (formatted for display by the UI).
 * @param priceText already-formatted price label (e.g. "30₪", "45-60₪") or null when free.
 */
data class RouteOption(
    val mode: TransportMode,
    val durationMinutes: Int,
    val priceText: String?
)

/** A WGS84 coordinate, kept framework-agnostic (mapped to mapsforge LatLong in UI). */
data class GeoPoint(
    val lat: Double,
    val lon: Double
)

/** One navigational instruction in a route detail view. */
data class RouteStep(
    val title: String,
    val subtitle: String?
)

/** Full step-by-step detail + map polyline for a selected [RouteOption]. */
data class RouteDetail(
    val mode: TransportMode,
    val steps: List<RouteStep>,
    val polyline: List<GeoPoint>
)

interface MotisRepository {
    /** Resolves a free-text place query to candidate names/addresses. */
    suspend fun geocode(query: String): List<String>

    /** Returns ranked route summaries between two places. */
    suspend fun getRoutes(origin: String, dest: String): List<RouteOption>

    /** Returns step-by-step instructions + a map polyline for one selected mode. */
    suspend fun getRouteDetail(origin: String, dest: String, mode: TransportMode): RouteDetail
}

/**
 * Offline-free stand-in used until the native MOTIS engine is linked. Adds a
 * short delay to mimic real computation so the "Calculating…" state is visible.
 */
class MockMotisRepository : MotisRepository {

    override suspend fun geocode(query: String): List<String> {
        delay(300L)
        return listOf(query)
    }

    override suspend fun getRoutes(origin: String, dest: String): List<RouteOption> {
        delay(1_000L)
        return listOf(
            RouteOption(TransportMode.TRANSIT, durationMinutes = 45, priceText = null),
            RouteOption(TransportMode.DRIVER, durationMinutes = 15, priceText = "30₪"),
            RouteOption(TransportMode.BIKE, durationMinutes = 22, priceText = null),
            RouteOption(TransportMode.WALKING, durationMinutes = 90, priceText = null),
            RouteOption(TransportMode.TAXI, durationMinutes = 15, priceText = "45-60₪")
        )
    }

    override suspend fun getRouteDetail(
        origin: String,
        dest: String,
        mode: TransportMode
    ): RouteDetail {
        delay(400L)
        val steps = listOf(
            RouteStep("יציאה מ$origin", null),
            RouteStep("המשך במסלול ה${mode.name.lowercase()}", null),
            RouteStep("הגעה ל$dest", null)
        )
        return RouteDetail(mode, steps, mockPolyline())
    }

    private fun mockPolyline(): List<GeoPoint> = listOf(
        GeoPoint(32.0853, 34.7818),
        GeoPoint(31.9900, 34.9500),
        GeoPoint(31.7683, 35.2137)
    )
}

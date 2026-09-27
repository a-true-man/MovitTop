package iam699030.gmail.movitop.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.nav.NavigationStep
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
 * A single route summary card. Several [TRANSIT][TransportMode.TRANSIT] options
 * can appear for the same search — e.g. two different lines from two nearby
 * stops a minute apart — so [id] (not [mode]) is what identifies *which*
 * specific itinerary this card is, both for the UI's DiffUtil and for fetching
 * its [RouteDetail] back from the same underlying result.
 *
 * @param durationMinutes trip duration in minutes (formatted for display by the UI).
 * @param priceText already-formatted price label (e.g. "30₪", "45-60₪") or null when free.
 * @param subtitle short distinguishing detail shown under the mode label — e.g.
 * which lines/transfers a transit option uses, since several can share the
 * same mode and duration ballpark.
 */
data class RouteOption(
    val id: String,
    val mode: TransportMode,
    val durationMinutes: Int,
    val priceText: String?,
    val subtitle: String? = null
)

/**
 * When to plan a trip for. [epochMillis] null means "now"; otherwise it's
 * either the desired departure time or, if [arriveBy], the desired arrival
 * time.
 */
data class TripTime(val epochMillis: Long? = null, val arriveBy: Boolean = false) {
    companion object {
        val NOW = TripTime()
    }
}

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

/**
 * Full step-by-step detail + map polyline for a selected [RouteOption].
 *
 * @param navigationSteps ordered walk/board/ride/alight cards for the live
 * navigation screen (empty for modes that don't support it yet).
 */
data class RouteDetail(
    val mode: TransportMode,
    val steps: List<RouteStep>,
    val polyline: List<GeoPoint>,
    val navigationSteps: List<NavigationStep> = emptyList()
)

interface MotisRepository {
    /** Resolves a free-text place query to rich autocomplete candidates. */
    suspend fun geocodePlaces(query: String): List<GeocodePlace>

    /** Returns ranked route summaries between two places. */
    suspend fun getRoutes(origin: String, dest: String, tripTime: TripTime = TripTime.NOW): List<RouteOption>

    /** Returns step-by-step instructions + a map polyline for one previously-returned [option]. */
    suspend fun getRouteDetail(
        origin: String,
        dest: String,
        option: RouteOption,
        tripTime: TripTime = TripTime.NOW
    ): RouteDetail
}

/**
 * Offline-free stand-in used until the native MOTIS engine is linked. Adds a
 * short delay to mimic real computation so the "Calculating…" state is visible.
 */
class MockMotisRepository : MotisRepository {

    override suspend fun geocodePlaces(query: String): List<GeocodePlace> {
        delay(200L)
        return PlaceSuggestions.localMatches(query).map { name ->
            GeocodePlace(name = name, subtitle = "ישראל", lat = 0.0, lon = 0.0)
        }.ifEmpty {
            listOf(GeocodePlace(name = query, subtitle = null, lat = 0.0, lon = 0.0))
        }
    }

    override suspend fun getRoutes(origin: String, dest: String, tripTime: TripTime): List<RouteOption> {
        delay(1_000L)
        return listOf(
            RouteOption("transit-0", TransportMode.TRANSIT, durationMinutes = 45, priceText = null, subtitle = "קו 480"),
            RouteOption("transit-1", TransportMode.TRANSIT, durationMinutes = 48, priceText = null, subtitle = "קו 142 + קו 5"),
            RouteOption("direct-DRIVER", TransportMode.DRIVER, durationMinutes = 15, priceText = "30₪"),
            RouteOption("direct-BIKE", TransportMode.BIKE, durationMinutes = 22, priceText = null),
            RouteOption("direct-WALK", TransportMode.WALKING, durationMinutes = 90, priceText = null),
            RouteOption("direct-TAXI", TransportMode.TAXI, durationMinutes = 15, priceText = "45-60₪")
        )
    }

    override suspend fun getRouteDetail(
        origin: String,
        dest: String,
        option: RouteOption,
        tripTime: TripTime
    ): RouteDetail {
        delay(400L)
        val mode = option.mode
        val steps = listOf(
            RouteStep("יציאה מ$origin", null),
            RouteStep("המשך במסלול ה${mode.name.lowercase()}", null),
            RouteStep("הגעה ל$dest", null)
        )
        val polyline = mockPolyline()
        val navSteps = listOf(
            NavigationStep.Walk("צא מ-$origin והמשך ישר", 300.0, polyline[0], estimatedSeconds = 230),
            NavigationStep.Walk("פנה שמאלה", 120.0, polyline[1], estimatedSeconds = 90),
            NavigationStep.Arrive(dest, polyline[2])
        )
        return RouteDetail(mode, steps, polyline, navSteps)
    }

    private fun mockPolyline(): List<GeoPoint> = listOf(
        GeoPoint(32.0853, 34.7818),
        GeoPoint(31.9900, 34.9500),
        GeoPoint(31.7683, 35.2137)
    )
}

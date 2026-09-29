package iam699030.gmail.movitop.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.nav.NavigationStep

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
 * @param departTimeText estimated clock time this option leaves ("14:20"), null when unknown.
 * @param arriveTimeText estimated clock time this option gets in, null when unknown.
 * @param departEpochMillis absolute departure time, null when unknown — formatted live as a
 * "leaves in X" countdown by [RelativeTime] on every bind, so it never goes stale while the
 * card stays on screen (see util/Ticker.kt).
 * @param distanceText already-formatted walk/bike distance (e.g. "1.3 ק״מ"), null for
 * modes where [priceText] is shown instead.
 * @param transitBadges the line(s) ridden, in order, for a [TransportMode.TRANSIT] option —
 * empty for every other mode.
 * @param viaStopText the first boarding stop's name, shown under a transit option's line badges.
 */
data class RouteOption(
    val id: String,
    val mode: TransportMode,
    val durationMinutes: Int,
    val priceText: String?,
    val subtitle: String? = null,
    val departTimeText: String? = null,
    val arriveTimeText: String? = null,
    val departEpochMillis: Long? = null,
    val distanceText: String? = null,
    val transitBadges: List<TransitLineBadge> = emptyList(),
    val viaStopText: String? = null
)

/** One line-number chip on a transit [RouteOption] card, colored like its map leg. */
data class TransitLineBadge(
    val label: String,
    val colorArgb: Int
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

/**
 * One navigational instruction in a route detail view.
 *
 * @param departTimeText clock time this leg starts (e.g. "14:20"), null when unknown.
 * @param arriveTimeText clock time this leg ends, null when unknown.
 * @param colorArgb the color this leg is drawn in on the map (see [legColorFor]) —
 * shown as the step's marker dot too, so a line change is visually obvious in both places.
 */
data class RouteStep(
    val title: String,
    val subtitle: String?,
    val departTimeText: String? = null,
    val arriveTimeText: String? = null,
    val colorArgb: Int = 0
)

/** One leg's polyline, colored so a line change or a walk is visually distinct on the map. */
data class MapLeg(
    val points: List<GeoPoint>,
    val colorArgb: Int
)

/**
 * Full step-by-step detail + map polyline for a selected [RouteOption].
 *
 * @param polyline every leg's points flattened, kept for camera-fit math.
 * @param legs the same points split per leg with a distinct [MapLeg.colorArgb] each,
 * for drawing the route as separate colored segments instead of one flat line.
 * @param navigationSteps ordered walk/board/ride/alight cards for the live
 * navigation screen (empty for modes that don't support it yet).
 */
data class RouteDetail(
    val mode: TransportMode,
    val steps: List<RouteStep>,
    val polyline: List<GeoPoint>,
    val legs: List<MapLeg> = emptyList(),
    val navigationSteps: List<NavigationStep> = emptyList()
)

/** One upcoming departure near a point, for the "nearby" screen. */
data class NearbyDeparture(
    val routeShortName: String,
    val headsign: String,
    val agencyName: String?,
    val stopName: String,
    val stopPoint: GeoPoint,
    val departTimeText: String,
    /** Absolute departure time — see [RouteOption.departEpochMillis] for why this isn't a frozen minute count. */
    val departEpochMillis: Long,
    val distanceMeters: Double,
    val colorArgb: Int
)

/** One transit stop (bus/rail/etc.) as shown as a map marker. */
data class TransitStopPlace(
    val stopId: String,
    val name: String,
    val point: GeoPoint
)

interface MotisRepository {
    /** Resolves a free-text place query to rich autocomplete candidates. */
    suspend fun geocodePlaces(query: String): List<GeocodePlace>

    /**
     * Returns ranked route summaries between two places.
     *
     * @param originCoord when non-null, used verbatim instead of re-geocoding
     * [origin] by name — the exact point the user already picked (or their
     * live GPS fix for "current location"), which free-text geocoding could
     * otherwise resolve to a different, wrong place of the same name.
     */
    suspend fun getRoutes(
        origin: String,
        dest: String,
        tripTime: TripTime = TripTime.NOW,
        originCoord: GeoPoint? = null,
        destCoord: GeoPoint? = null
    ): List<RouteOption>

    /** Returns step-by-step instructions + a map polyline for one previously-returned [option]. */
    suspend fun getRouteDetail(
        origin: String,
        dest: String,
        option: RouteOption,
        tripTime: TripTime = TripTime.NOW,
        originCoord: GeoPoint? = null,
        destCoord: GeoPoint? = null
    ): RouteDetail

    /** Upcoming departures within [radiusMeters] of [point], soonest first. */
    suspend fun nearbyDepartures(point: GeoPoint, radiusMeters: Int = 700): List<NearbyDeparture>

    /** Transit stops inside the [southWest]..[northEast] map viewport (see [TransitStopPlace]). */
    suspend fun stopsInBounds(southWest: GeoPoint, northEast: GeoPoint): List<TransitStopPlace>

    /**
     * Upcoming departures at [stopId], soonest first — powers the map's
     * station-tap bottom sheet. [point] is that stop's own coordinate (used
     * only to satisfy [NearbyDeparture]'s distance field, which the stop
     * sheet doesn't display). [windowSeconds] widens the search past the
     * default handful of near-term departures — pass a large value (e.g. a
     * full day) together with a high [n] to get a line's full remaining
     * schedule for the day when drilling into one route.
     */
    suspend fun departuresAtStop(
        stopId: String,
        point: GeoPoint,
        windowSeconds: Int = 2 * 60 * 60,
        n: Int = 20
    ): List<NearbyDeparture>
}

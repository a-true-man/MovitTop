package iam699030.gmail.movitop.data

import android.content.Context
import android.util.Log
import iam699030.gmail.movitop.BuildConfig
import iam699030.gmail.movitop.MotisBinaryManager
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.api.IsoTime
import iam699030.gmail.movitop.data.api.ItineraryDto
import iam699030.gmail.movitop.data.toGeocodePlace
import iam699030.gmail.movitop.data.api.LegDto
import iam699030.gmail.movitop.data.api.MotisApi
import iam699030.gmail.movitop.data.api.PolylineDecoder
import iam699030.gmail.movitop.data.api.StepDto
import iam699030.gmail.movitop.data.api.StopTimeDto
import iam699030.gmail.movitop.nav.NavigationStep
import iam699030.gmail.movitop.nav.RideStop
import iam699030.gmail.movitop.nav.haversineMeters
import iam699030.gmail.movitop.nav.pointAtFraction
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "RealMotisRepository"
private const val MAX_WALK_MINUTES = 180
private const val MAX_BIKE_MINUTES = 240
private const val MAX_TRANSIT_OPTIONS = 4
private const val BADGE_LABEL_MAX_CHARS = 10

/**
 * Talks to the MOTIS HTTP server started on-device by [iam699030.gmail.movitop.MotisForegroundService]
 * (127.0.0.1, no network egress — required for full offline operation).
 */
class RealMotisRepository(
    private val context: Context,
    baseUrl: String = "${MotisBinaryManager.BASE_URL}/"
) : MotisRepository {

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        // BASIC still builds a log line for every request/response
                        // and writes it to Logcat — real, if small, CPU/IO cost
                        // that release builds shouldn't pay for on every search.
                        level = if (BuildConfig.DEBUG) {
                            HttpLoggingInterceptor.Level.BASIC
                        } else {
                            HttpLoggingInterceptor.Level.NONE
                        }
                    }
                )
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api: MotisApi = retrofit.create(MotisApi::class.java)
    private val geocoder = PlaceGeocoder(api)

    // Broad/short geocode prefixes (e.g. 2 typed characters) can take MOTIS's
    // geocoder tens of seconds to rank on this device — fine for a deliberate
    // "plan route" submit, but autocomplete-while-typing already treats any
    // failure as "no suggestions this keystroke" and falls back to local
    // matches (see geocodePlaces below), so it should fail fast instead of
    // blocking on the shared 30s read timeout meant for real route requests.
    private val autocompleteApi: MotisApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MotisApi::class.java)

    // Several TRANSIT options can share the same [TransportMode] (e.g. two
    // different lines a minute apart), so getRouteDetail can't re-derive which
    // one was picked from mode alone — this remembers the exact itinerary each
    // RouteOption.id pointed to from the most recent getRoutes() call.
    private val itineraryCache = mutableMapOf<String, ItineraryDto>()

    override suspend fun geocodePlaces(query: String): List<GeocodePlace> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val local = PlaceSuggestions.localMatches(trimmed).map { name ->
            GeocodePlace(name = name, subtitle = context.getString(R.string.country_israel), lat = 0.0, lon = 0.0)
        }
        val remote = try {
            autocompleteApi.geocode(trimmed).mapNotNull { it.toGeocodePlace(context) }
        } catch (e: CancellationException) {
            // A newer keystroke superseded this search — not a real failure,
            // and swallowing it here would break structured concurrency.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "geocode(\"$trimmed\") failed — is the MOTIS engine running?", e)
            emptyList()
        }
        return (remote + local)
            .distinctBy { it.displayKey }
            .take(12)
    }

    override suspend fun getRoutes(
        origin: String,
        dest: String,
        tripTime: TripTime,
        originCoord: GeoPoint?,
        destCoord: GeoPoint?
    ): List<RouteOption> {
        val from = resolvePlace(origin, originCoord, placeContext = dest)
        val to = resolvePlace(dest, destCoord, placeContext = origin)
        val plan = api.plan(
            fromPlace = from,
            toPlace = to,
            time = isoFor(tripTime.epochMillis),
            transitModes = "TRANSIT",
            directModes = "WALK,BIKE,CAR",
            arriveBy = tripTime.arriveBy
        )

        itineraryCache.clear()
        val options = mutableListOf<RouteOption>()

        plan.itineraries.orEmpty()
            .sortedBy { minutesOf(it) }
            .take(MAX_TRANSIT_OPTIONS)
            .forEachIndexed { i, itinerary ->
                val id = "transit-$i"
                itineraryCache[id] = itinerary
                val transitLegs = itinerary.legs.orEmpty().filter { isTransitLeg(it) }
                options += RouteOption(
                    id = id,
                    mode = TransportMode.TRANSIT,
                    durationMinutes = minutesOf(itinerary),
                    priceText = null,
                    subtitle = transitSubtitle(itinerary),
                    departTimeText = IsoTime.toClockText(itinerary.startTime),
                    arriveTimeText = IsoTime.toClockText(itinerary.endTime),
                    departEpochMillis = IsoTime.toEpochMillis(itinerary.startTime),
                    transitBadges = transitLegs.map { TransitLineBadge(badgeLabel(it), LegColors.forLeg(it)) },
                    viaStopText = transitLegs.firstOrNull()?.from?.name?.let {
                        getString(R.string.route_option_via_stop, it)
                    }
                )
            }

        plan.direct.orEmpty().forEach { itinerary ->
            val mode = itinerary.legs?.firstOrNull()?.mode ?: return@forEach
            val minutes = minutesOf(itinerary)
            val meters = itinerary.legs.sumOf { it.distance ?: 0.0 }
            val depart = IsoTime.toClockText(itinerary.startTime)
            val arrive = IsoTime.toClockText(itinerary.endTime)
            when (mode) {
                // maxDirectTime is raised well past 30 min so long CAR trips aren't
                // dropped (see MotisApi.plan) — but that means WALK/BIKE can now
                // come back with equally long, unrealistic durations for the same
                // distance (e.g. an 11h "walk" for a 60km intercity trip); cap
                // those two to what's actually a sane suggestion. CAR/TAXI are
                // intentionally left uncapped — a multi-hour drive is normal.
                "WALK" -> if (minutes <= MAX_WALK_MINUTES) {
                    itineraryCache["direct-WALK"] = itinerary
                    options += RouteOption(
                        "direct-WALK", TransportMode.WALKING, minutes, null,
                        departTimeText = depart, arriveTimeText = arrive, distanceText = distanceText(meters)
                    )
                }
                "BIKE" -> if (minutes <= MAX_BIKE_MINUTES) {
                    itineraryCache["direct-BIKE"] = itinerary
                    options += RouteOption(
                        "direct-BIKE", TransportMode.BIKE, minutes, null,
                        departTimeText = depart, arriveTimeText = arrive, distanceText = distanceText(meters)
                    )
                }
                "CAR" -> {
                    itineraryCache["direct-DRIVER"] = itinerary
                    itineraryCache["direct-TAXI"] = itinerary
                    options += RouteOption("direct-DRIVER", TransportMode.DRIVER, minutes, driverPrice(meters), departTimeText = depart, arriveTimeText = arrive)
                    options += RouteOption("direct-TAXI", TransportMode.TAXI, minutes, taxiPrice(meters), departTimeText = depart, arriveTimeText = arrive)
                }
            }
        }
        return options
    }

    override suspend fun getRouteDetail(
        origin: String,
        dest: String,
        option: RouteOption,
        tripTime: TripTime,
        originCoord: GeoPoint?,
        destCoord: GeoPoint?
    ): RouteDetail {
        val cached = itineraryCache[option.id]
        val itinerary = cached ?: run {
            // Cache miss (e.g. repository was recreated since getRoutes()) —
            // re-query and best-effort pick the closest match by mode/duration
            // rather than failing outright.
            val from = resolvePlace(origin, originCoord, placeContext = dest)
            val to = resolvePlace(dest, destCoord, placeContext = origin)
            val mode = option.mode
            val transit = if (mode == TransportMode.TRANSIT) "TRANSIT" else ""
            val direct = when (mode) {
                TransportMode.WALKING -> "WALK"
                TransportMode.BIKE -> "BIKE"
                TransportMode.DRIVER, TransportMode.TAXI -> "CAR"
                TransportMode.TRANSIT -> ""
            }
            val plan = api.plan(from, to, isoFor(tripTime.epochMillis), transit, direct, tripTime.arriveBy)
            val candidates = if (mode == TransportMode.TRANSIT) plan.itineraries else plan.direct
            candidates.orEmpty().minByOrNull { kotlin.math.abs(minutesOf(it) - option.durationMinutes) }
        }

        val legs = itinerary?.legs.orEmpty()
        val steps = legs.map { leg -> buildRouteStep(leg) }
        val mapLegs = legs.map { leg ->
            MapLeg(
                points = PolylineDecoder.decode(leg.legGeometry?.points, leg.legGeometry?.precision ?: 7),
                colorArgb = LegColors.forLeg(leg)
            )
        }
        val polyline = mapLegs.flatMap { it.points }
        return RouteDetail(option.mode, steps, polyline, mapLegs, buildNavigationSteps(legs, dest))
    }

    override suspend fun nearbyDepartures(point: GeoPoint, radiusMeters: Int): List<NearbyDeparture> {
        val response = try {
            api.stoptimes(center = "${point.lat},${point.lon}", radius = radiusMeters)
        } catch (e: Exception) {
            Log.w(TAG, "stoptimes($point, $radiusMeters) failed — is the MOTIS engine running?", e)
            return emptyList()
        }
        return response.stopTimes.orEmpty().mapNotNull { it.toNearbyDeparture(point) }
    }

    override suspend fun stopsInBounds(southWest: GeoPoint, northEast: GeoPoint): List<TransitStopPlace> {
        // MOTIS's /map/stops takes two diagonal *screen* corners, not a plain
        // (minLat,minLon)/(maxLat,maxLon) AABB: "min" is the south-EAST corner
        // (min latitude, max longitude) and "max" is the north-WEST corner
        // (max latitude, min longitude) — see openapi.yaml's "lower right" /
        // "upper left" wording for /api/v6/map/stops.
        val places = try {
            api.mapStops(
                min = "${southWest.lat},${northEast.lon}",
                max = "${northEast.lat},${southWest.lon}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "mapStops($southWest, $northEast) failed — is the MOTIS engine running?", e)
            return emptyList()
        }
        return places.mapNotNull { place ->
            val stopId = place.stopId ?: return@mapNotNull null
            val lat = place.lat ?: return@mapNotNull null
            val lon = place.lon ?: return@mapNotNull null
            TransitStopPlace(stopId, place.name ?: "", GeoPoint(lat, lon))
        }
    }

    override suspend fun departuresAtStop(
        stopId: String,
        point: GeoPoint,
        windowSeconds: Int,
        n: Int
    ): List<NearbyDeparture> {
        val response = try {
            api.stoptimesForStop(stopId = stopId, n = n, window = windowSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "stoptimesForStop(\"$stopId\") failed — is the MOTIS engine running?", e)
            return emptyList()
        }
        return response.stopTimes.orEmpty().mapNotNull { it.toNearbyDeparture(point) }
    }

    private fun StopTimeDto.toNearbyDeparture(origin: GeoPoint): NearbyDeparture? {
        val stopPlace = place ?: return null
        val lat = stopPlace.lat ?: return null
        val lon = stopPlace.lon ?: return null
        val departIso = stopPlace.departure ?: stopPlace.arrival ?: return null
        val epoch = IsoTime.toEpochMillis(departIso) ?: return null
        val stopPoint = GeoPoint(lat, lon)
        val shortName = routeShortName?.takeIf { it.isNotBlank() }
        val headsignText = headsign?.takeIf { it.isNotBlank() }
        val label = shortName ?: routeLongName?.takeIf { it.isNotBlank() } ?: mode.orEmpty()
        return NearbyDeparture(
            routeShortName = label,
            headsign = headsignText ?: routeLongName.orEmpty(),
            agencyName = agencyName,
            stopName = stopPlace.name ?: "",
            stopPoint = stopPoint,
            departTimeText = IsoTime.toClockText(departIso) ?: "",
            departEpochMillis = epoch,
            distanceMeters = haversineMeters(origin, stopPoint),
            colorArgb = routeColor?.takeIf { it.isNotBlank() }?.let { LegColors.fallbackFor(it) }
                ?: LegColors.fallbackFor(label)
        )
    }

    /** "lat,lon" straight from [coord] when known, otherwise geocode [place] by name. */
    private suspend fun resolvePlace(place: String, coord: GeoPoint?, placeContext: String?): String =
        coord?.let { "${it.lat},${it.lon}" } ?: geocoder.resolve(place, placeContext)

    private fun isTransitLeg(leg: LegDto) =
        leg.mode != null && leg.mode != "WALK" && leg.mode != "BIKE" && leg.mode != "CAR"

    /**
     * A line's display name: [LegDto.routeShortName] when there's a real one,
     * else headsign, else the raw mode. MOTIS reports a *blank* (not absent)
     * routeShortName for some lines — `?:` alone doesn't fall through an
     * empty string, so this explicitly does.
     */
    private fun lineLabel(leg: LegDto): String =
        leg.routeShortName?.takeIf { it.isNotBlank() }
            ?: leg.headsign?.takeIf { it.isNotBlank() }
            ?: leg.mode.orEmpty()

    /**
     * [lineLabel], capped for a route-summary card's badge (see [RouteAdapter]).
     * Most lines have a compact routeShortName (e.g. "480"), but a few —
     * commuter rail especially — report a full corridor description there
     * instead (e.g. "Modiin Center-Modiin Maccabim Reut<->Netanya"), same as
     * the headsign/mode fallback for lines with no short name at all; either
     * way, cap it so the badge can't blow up.
     */
    private fun badgeLabel(leg: LegDto): String {
        val label = lineLabel(leg)
        return if (label.length > BADGE_LABEL_MAX_CHARS) {
            label.take(BADGE_LABEL_MAX_CHARS) + "…"
        } else {
            label
        }
    }

    /** e.g. "480" or "142 + 5" — lets the UI tell apart several TRANSIT options at a glance. */
    private fun transitSubtitle(itinerary: ItineraryDto): String? {
        val lines = itinerary.legs.orEmpty().filter { isTransitLeg(it) }.map { lineLabel(it) }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    private fun distanceText(meters: Double): String =
        if (meters >= 1000) getString(R.string.distance_km, meters / 1000.0)
        else getString(R.string.distance_meters, meters.roundToInt())

    private fun buildRouteStep(leg: LegDto): RouteStep = RouteStep(
        title = stepTitle(leg),
        subtitle = stepSubtitle(leg),
        departTimeText = IsoTime.toClockText(leg.startTime),
        arriveTimeText = IsoTime.toClockText(leg.endTime),
        colorArgb = LegColors.forLeg(leg)
    )

    /** Walk/board/ride/arrive cards for the live navigation screen (see nav/). */
    private fun buildNavigationSteps(legs: List<LegDto>, destName: String): List<NavigationStep> {
        val navSteps = mutableListOf<NavigationStep>()
        val isStreetMode = { m: String? -> m == "WALK" || m == "BIKE" || m == "CAR" }

        legs.forEach { leg ->
            val to = leg.to
            val toPoint = GeoPoint(to?.lat ?: 0.0, to?.lon ?: 0.0)
            val legPolyline = PolylineDecoder.decode(
                leg.legGeometry?.points,
                leg.legGeometry?.precision ?: 7
            )

            if (isStreetMode(leg.mode)) {
                val legSteps = leg.steps.orEmpty()
                if (legSteps.isEmpty() || legPolyline.isEmpty()) {
                    navSteps += NavigationStep.Walk(
                        instruction = getString(R.string.nav_walk_continue_to, to?.name.orEmpty()),
                        distanceMeters = leg.distance ?: 0.0,
                        point = toPoint,
                        estimatedSeconds = leg.duration ?: walkSeconds(leg.distance ?: 0.0),
                        legPoints = legPolyline
                    )
                } else {
                    val totalDistance = legSteps.sumOf { it.distance ?: 0.0 }.takeIf { it > 0 }
                        ?: (leg.distance ?: 0.0).takeIf { it > 0 } ?: 1.0
                    var cumulative = 0.0
                    legSteps.forEach { step ->
                        val stepDistance = step.distance ?: 0.0
                        cumulative += stepDistance
                        navSteps += NavigationStep.Walk(
                            instruction = stepInstructionText(step),
                            distanceMeters = stepDistance,
                            point = pointAtFraction(legPolyline, cumulative / totalDistance),
                            estimatedSeconds = walkSeconds(stepDistance),
                            legPoints = legPolyline
                        )
                    }
                }
            } else {
                val routeLabel = routeLabelFor(leg)
                val color = LegColors.forLeg(leg)
                val from = leg.from
                navSteps += NavigationStep.Board(
                    stopName = from?.name ?: "",
                    routeLabel = routeLabel,
                    agencyName = leg.agencyName,
                    point = GeoPoint(from?.lat ?: 0.0, from?.lon ?: 0.0),
                    departTimeText = IsoTime.toClockText(leg.startTime),
                    colorArgb = color
                )
                val remainingStops = leg.intermediateStops.orEmpty().mapNotNull { place ->
                    val lat = place.lat ?: return@mapNotNull null
                    val lon = place.lon ?: return@mapNotNull null
                    RideStop(
                        name = place.name.orEmpty(),
                        point = GeoPoint(lat, lon),
                        arriveTimeText = IsoTime.toClockText(place.arrival ?: place.departure)
                    )
                } + RideStop(
                    name = to?.name.orEmpty(),
                    point = toPoint,
                    arriveTimeText = IsoTime.toClockText(leg.endTime)
                )
                navSteps += NavigationStep.Ride(
                    routeLabel = routeLabel,
                    alightStopName = to?.name ?: "",
                    point = toPoint,
                    estimatedSeconds = leg.duration ?: 0L,
                    arriveTimeText = IsoTime.toClockText(leg.endTime),
                    colorArgb = color,
                    legPoints = legPolyline,
                    stops = remainingStops
                )
            }
        }

        legs.lastOrNull()?.to?.let { last ->
            navSteps += NavigationStep.Arrive(
                placeName = destName,
                point = GeoPoint(last.lat ?: 0.0, last.lon ?: 0.0),
                arriveTimeText = IsoTime.toClockText(legs.lastOrNull()?.endTime)
            )
        }
        return navSteps
    }

    private fun routeLabelFor(leg: LegDto): String {
        val line = lineLabel(leg)
        val headsign = leg.headsign?.takeIf { it.isNotBlank() }
        return if (headsign != null) {
            getString(R.string.route_label_with_headsign, line, headsign)
        } else {
            getString(R.string.route_label_plain, line)
        }
    }

    /** Fallback duration estimate (~4.7 km/h) when MOTIS doesn't give one directly. */
    private fun walkSeconds(distanceMeters: Double): Long =
        (distanceMeters / 1.3).roundToInt().toLong().coerceAtLeast(5L)

    private fun stepInstructionText(step: StepDto): String {
        val street = step.streetName?.takeIf { it.isNotBlank() }
        val directionText = when (step.relativeDirection) {
            "DEPART" -> getString(R.string.nav_dir_depart)
            "LEFT" -> getString(R.string.nav_dir_left)
            "HARD_LEFT" -> getString(R.string.nav_dir_hard_left)
            "SLIGHTLY_LEFT" -> getString(R.string.nav_dir_slightly_left)
            "RIGHT" -> getString(R.string.nav_dir_right)
            "HARD_RIGHT" -> getString(R.string.nav_dir_hard_right)
            "SLIGHTLY_RIGHT" -> getString(R.string.nav_dir_slightly_right)
            "UTURN_LEFT", "UTURN_RIGHT" -> getString(R.string.nav_dir_uturn)
            "CONTINUE" -> getString(R.string.nav_dir_continue)
            "ELEVATOR" -> getString(R.string.nav_dir_elevator)
            "STAIRS" -> getString(R.string.nav_dir_stairs)
            else -> getString(R.string.nav_dir_default)
        }
        return if (street != null) getString(R.string.nav_dir_with_street, directionText, street) else directionText
    }

    // --- helpers --------------------------------------------------------------

    private fun minutesOf(itinerary: ItineraryDto): Int {
        val seconds = itinerary.duration
            ?: itinerary.legs?.sumOf { it.duration ?: 0L }
            ?: 0L
        return (seconds / 60.0).roundToInt().coerceAtLeast(1)
    }

    private fun stepTitle(leg: LegDto): String {
        val destination = leg.to?.name ?: ""
        return when (leg.mode) {
            "WALK" -> getString(R.string.route_step_walk_to, destination)
            "BIKE" -> getString(R.string.route_step_bike_to, destination)
            "CAR" -> getString(R.string.route_step_drive_to, destination)
            else -> getString(R.string.route_step_transit_to, lineLabel(leg), destination)
        }
    }

    private fun stepSubtitle(leg: LegDto): String? {
        val minutes = ((leg.duration ?: 0L) / 60.0).roundToInt()
        val agency = leg.agencyName
        return when {
            agency != null && minutes > 0 -> "$agency · ${getString(R.string.duration_minutes, minutes)}"
            minutes > 0 -> getString(R.string.duration_minutes, minutes)
            else -> agency
        }
    }

    private fun driverPrice(meters: Double): String {
        val price = estimateDriverPrice(meters / 1000.0)
        return "≈$price₪"
    }

    private fun taxiPrice(meters: Double): String {
        val price = estimateTaxiPrice(meters / 1000.0)
        return "≈$price₪"
    }

    /** [epochMillis] null means "now". */
    private fun isoFor(epochMillis: Long?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(epochMillis?.let { Date(it) } ?: Date())
    }

    private fun getString(resId: Int, vararg args: Any): String = context.getString(resId, *args)
}

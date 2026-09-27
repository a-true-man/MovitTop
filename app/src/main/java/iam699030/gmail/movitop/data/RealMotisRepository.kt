package iam699030.gmail.movitop.data

import iam699030.gmail.movitop.MotisBinaryManager
import iam699030.gmail.movitop.data.api.ItineraryDto
import iam699030.gmail.movitop.data.toGeocodePlace
import iam699030.gmail.movitop.data.api.LegDto
import iam699030.gmail.movitop.data.api.MotisApi
import iam699030.gmail.movitop.data.api.PolylineDecoder
import iam699030.gmail.movitop.data.api.StepDto
import iam699030.gmail.movitop.nav.NavigationStep
import iam699030.gmail.movitop.nav.pointAtFraction
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

private const val MAX_WALK_MINUTES = 180
private const val MAX_BIKE_MINUTES = 240
private const val MAX_TRANSIT_OPTIONS = 4

/**
 * Talks to the MOTIS HTTP server started on-device by [iam699030.gmail.movitop.MotisForegroundService]
 * (127.0.0.1, no network egress — required for full offline operation).
 */
class RealMotisRepository(
    baseUrl: String = "${MotisBinaryManager.BASE_URL}/"
) : MotisRepository {

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
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

    // Several TRANSIT options can share the same [TransportMode] (e.g. two
    // different lines a minute apart), so getRouteDetail can't re-derive which
    // one was picked from mode alone — this remembers the exact itinerary each
    // RouteOption.id pointed to from the most recent getRoutes() call.
    private val itineraryCache = mutableMapOf<String, ItineraryDto>()

    override suspend fun geocodePlaces(query: String): List<GeocodePlace> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val local = PlaceSuggestions.localMatches(trimmed).map { name ->
            GeocodePlace(name = name, subtitle = "ישראל", lat = 0.0, lon = 0.0)
        }
        val remote = try {
            api.geocode(trimmed).mapNotNull { it.toGeocodePlace() }
        } catch (_: Exception) {
            emptyList()
        }
        return (remote + local)
            .distinctBy { it.displayKey }
            .take(12)
    }

    override suspend fun getRoutes(origin: String, dest: String, tripTime: TripTime): List<RouteOption> {
        val from = geocoder.resolve(origin, context = dest)
        val to = geocoder.resolve(dest, context = origin)
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
                options += RouteOption(
                    id = id,
                    mode = TransportMode.TRANSIT,
                    durationMinutes = minutesOf(itinerary),
                    priceText = null,
                    subtitle = transitSubtitle(itinerary)
                )
            }

        plan.direct.orEmpty().forEach { itinerary ->
            val mode = itinerary.legs?.firstOrNull()?.mode ?: return@forEach
            val minutes = minutesOf(itinerary)
            val meters = itinerary.legs.sumOf { it.distance ?: 0.0 }
            when (mode) {
                // maxDirectTime is raised well past 30 min so long CAR trips aren't
                // dropped (see MotisApi.plan) — but that means WALK/BIKE can now
                // come back with equally long, unrealistic durations for the same
                // distance (e.g. an 11h "walk" for a 60km intercity trip); cap
                // those two to what's actually a sane suggestion. CAR/TAXI are
                // intentionally left uncapped — a multi-hour drive is normal.
                "WALK" -> if (minutes <= MAX_WALK_MINUTES) {
                    itineraryCache["direct-WALK"] = itinerary
                    options += RouteOption("direct-WALK", TransportMode.WALKING, minutes, null)
                }
                "BIKE" -> if (minutes <= MAX_BIKE_MINUTES) {
                    itineraryCache["direct-BIKE"] = itinerary
                    options += RouteOption("direct-BIKE", TransportMode.BIKE, minutes, null)
                }
                "CAR" -> {
                    itineraryCache["direct-DRIVER"] = itinerary
                    itineraryCache["direct-TAXI"] = itinerary
                    options += RouteOption("direct-DRIVER", TransportMode.DRIVER, minutes, driverPrice(meters))
                    options += RouteOption("direct-TAXI", TransportMode.TAXI, minutes, taxiPrice(meters))
                }
            }
        }
        return options
    }

    override suspend fun getRouteDetail(
        origin: String,
        dest: String,
        option: RouteOption,
        tripTime: TripTime
    ): RouteDetail {
        val cached = itineraryCache[option.id]
        val itinerary = cached ?: run {
            // Cache miss (e.g. repository was recreated since getRoutes()) —
            // re-query and best-effort pick the closest match by mode/duration
            // rather than failing outright.
            val from = geocoder.resolve(origin, context = dest)
            val to = geocoder.resolve(dest, context = origin)
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
        val steps = legs.map { leg -> RouteStep(stepTitle(leg), stepSubtitle(leg)) }
        val polyline = legs.flatMap { leg ->
            PolylineDecoder.decode(
                leg.legGeometry?.points,
                leg.legGeometry?.precision ?: 7
            )
        }
        return RouteDetail(option.mode, steps, polyline, buildNavigationSteps(legs, dest))
    }

    /** e.g. "480" or "142 + 5" — lets the UI tell apart several TRANSIT options at a glance. */
    private fun transitSubtitle(itinerary: ItineraryDto): String? {
        val lines = itinerary.legs.orEmpty()
            .filter { it.mode != null && it.mode != "WALK" && it.mode != "BIKE" && it.mode != "CAR" }
            .mapNotNull { it.routeShortName ?: it.headsign }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

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
                        instruction = "המשך אל ${to?.name ?: ""}",
                        distanceMeters = leg.distance ?: 0.0,
                        point = toPoint,
                        estimatedSeconds = leg.duration ?: walkSeconds(leg.distance ?: 0.0)
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
                            estimatedSeconds = walkSeconds(stepDistance)
                        )
                    }
                }
            } else {
                val line = leg.routeShortName ?: leg.headsign ?: leg.mode.orEmpty()
                val routeLabel = if (leg.headsign != null) "קו $line לכיוון ${leg.headsign}" else "קו $line"
                val from = leg.from
                navSteps += NavigationStep.Board(
                    stopName = from?.name ?: "",
                    routeLabel = routeLabel,
                    agencyName = leg.agencyName,
                    point = GeoPoint(from?.lat ?: 0.0, from?.lon ?: 0.0)
                )
                navSteps += NavigationStep.Ride(
                    routeLabel = routeLabel,
                    alightStopName = to?.name ?: "",
                    point = toPoint,
                    estimatedSeconds = leg.duration ?: 0L
                )
            }
        }

        legs.lastOrNull()?.to?.let { last ->
            navSteps += NavigationStep.Arrive(destName, GeoPoint(last.lat ?: 0.0, last.lon ?: 0.0))
        }
        return navSteps
    }

    /** Fallback duration estimate (~4.7 km/h) when MOTIS doesn't give one directly. */
    private fun walkSeconds(distanceMeters: Double): Long =
        (distanceMeters / 1.3).roundToInt().toLong().coerceAtLeast(5L)

    private fun stepInstructionText(step: StepDto): String {
        val street = step.streetName?.takeIf { it.isNotBlank() }
        val directionText = when (step.relativeDirection) {
            "DEPART" -> "צא לדרך"
            "LEFT" -> "פנה שמאלה"
            "HARD_LEFT" -> "פנה שמאלה בחדות"
            "SLIGHTLY_LEFT" -> "פנה מעט שמאלה"
            "RIGHT" -> "פנה ימינה"
            "HARD_RIGHT" -> "פנה ימינה בחדות"
            "SLIGHTLY_RIGHT" -> "פנה מעט ימינה"
            "UTURN_LEFT", "UTURN_RIGHT" -> "בצע פרסה"
            "CONTINUE" -> "המשך ישר"
            "ELEVATOR" -> "עלה במעלית"
            "STAIRS" -> "עלה/רד במדרגות"
            else -> "המשך"
        }
        return if (street != null) "$directionText אל $street" else directionText
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
            "WALK" -> "הליכה אל $destination"
            "BIKE" -> "רכיבה אל $destination"
            "CAR" -> "נסיעה אל $destination"
            else -> {
                val line = leg.routeShortName ?: leg.headsign ?: leg.mode.orEmpty()
                "קו $line אל $destination"
            }
        }
    }

    private fun stepSubtitle(leg: LegDto): String? {
        val minutes = ((leg.duration ?: 0L) / 60.0).roundToInt()
        val agency = leg.agencyName
        return when {
            agency != null && minutes > 0 -> "$agency · $minutes דק׳"
            minutes > 0 -> "$minutes דק׳"
            else -> agency
        }
    }

    private fun driverPrice(meters: Double): String {
        val price = (8 + meters / 1000.0 * 1.2).roundToInt()
        return "≈$price₪"
    }

    private fun taxiPrice(meters: Double): String {
        val low = (12 + meters / 1000.0 * 3.5).roundToInt()
        val high = (low * 1.25).roundToInt()
        return "≈$low-$high₪"
    }

    /** [epochMillis] null means "now". */
    private fun isoFor(epochMillis: Long?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(epochMillis?.let { Date(it) } ?: Date())
    }
}

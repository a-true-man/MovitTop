package iam699030.gmail.movitop.data

import iam699030.gmail.movitop.data.api.ItineraryDto
import iam699030.gmail.movitop.data.toGeocodePlace
import iam699030.gmail.movitop.data.api.LegDto
import iam699030.gmail.movitop.data.api.MotisApi
import iam699030.gmail.movitop.data.api.PolylineDecoder
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

/**
 * Talks to the MOTIS HTTP server. During development the macOS Docker instance
 * is reachable from the Android emulator at 10.0.2.2; switch to
 * [MotisBinaryManager.BASE_URL] once the on-device ARM64 binary is bundled.
 */
class RealMotisRepository(
    baseUrl: String = "http://10.0.2.2:8080/"
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

    override suspend fun getRoutes(origin: String, dest: String): List<RouteOption> {
        val from = geocoder.resolve(origin, context = dest)
        val to = geocoder.resolve(dest, context = origin)
        val plan = api.plan(
            fromPlace = from,
            toPlace = to,
            time = nowIso(),
            transitModes = "TRANSIT",
            directModes = "WALK,BIKE,CAR"
        )

        val options = mutableListOf<RouteOption>()

        plan.itineraries?.firstOrNull()?.let { itinerary ->
            options += RouteOption(TransportMode.TRANSIT, minutesOf(itinerary), priceText = null)
        }

        plan.direct.orEmpty().forEach { itinerary ->
            val mode = itinerary.legs?.firstOrNull()?.mode ?: return@forEach
            val minutes = minutesOf(itinerary)
            val meters = itinerary.legs.sumOf { it.distance ?: 0.0 }
            when (mode) {
                "WALK" -> options += RouteOption(TransportMode.WALKING, minutes, null)
                "BIKE" -> options += RouteOption(TransportMode.BIKE, minutes, null)
                "CAR" -> {
                    options += RouteOption(TransportMode.DRIVER, minutes, driverPrice(meters))
                    options += RouteOption(TransportMode.TAXI, minutes, taxiPrice(meters))
                }
            }
        }
        return options
    }

    override suspend fun getRouteDetail(
        origin: String,
        dest: String,
        mode: TransportMode
    ): RouteDetail {
        val from = geocoder.resolve(origin, context = dest)
        val to = geocoder.resolve(dest, context = origin)
        val transit = if (mode == TransportMode.TRANSIT) "TRANSIT" else ""
        val direct = when (mode) {
            TransportMode.WALKING -> "WALK"
            TransportMode.BIKE -> "BIKE"
            TransportMode.DRIVER, TransportMode.TAXI -> "CAR"
            TransportMode.TRANSIT -> ""
        }
        val plan = api.plan(from, to, nowIso(), transit, direct)

        val itinerary = if (mode == TransportMode.TRANSIT) {
            plan.itineraries?.firstOrNull()
        } else {
            plan.direct?.firstOrNull()
        }

        val legs = itinerary?.legs.orEmpty()
        val steps = legs.map { leg -> RouteStep(stepTitle(leg), stepSubtitle(leg)) }
        val polyline = legs.flatMap { leg ->
            PolylineDecoder.decode(
                leg.legGeometry?.points,
                leg.legGeometry?.precision ?: 7
            )
        }
        return RouteDetail(mode, steps, polyline)
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

    private fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}

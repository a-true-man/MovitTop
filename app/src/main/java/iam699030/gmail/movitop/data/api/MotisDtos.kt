package iam699030.gmail.movitop.data.api

/**
 * Minimal DTOs for the MOTIS REST API. Only the fields Movitop needs are
 * declared; Gson ignores the (verbose) remainder of the responses.
 */

/** One entry of `GET /api/v1/geocode` (the endpoint returns a JSON array). */
data class GeocodeDto(
    val name: String?,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val id: String? = null,
    val type: String? = null
)

/** `GET /api/v1/plan` response. */
data class PlanResponseDto(
    val itineraries: List<ItineraryDto>? = null,
    val direct: List<ItineraryDto>? = null
)

data class ItineraryDto(
    val duration: Long? = null, // seconds
    val startTime: String? = null,
    val endTime: String? = null,
    val transfers: Int? = null,
    val legs: List<LegDto>? = null
)

data class LegDto(
    val mode: String? = null, // WALK, BIKE, CAR, BUS, RAIL, ...
    val duration: Long? = null, // seconds
    val distance: Double? = null, // meters
    val from: PlaceDto? = null,
    val to: PlaceDto? = null,
    val headsign: String? = null,
    val routeShortName: String? = null,
    val routeLongName: String? = null,
    val agencyName: String? = null,
    val legGeometry: LegGeometryDto? = null,
    val steps: List<StepDto>? = null
)

data class PlaceDto(
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null
)

/** Encoded Google polyline + the precision used to encode it (7 for /api/v1). */
data class LegGeometryDto(
    val points: String? = null,
    val precision: Int? = null,
    val length: Int? = null
)

data class StepDto(
    val relativeDirection: String? = null,
    val streetName: String? = null,
    val distance: Double? = null
)

package iam699030.gmail.movitop.data.api

import retrofit2.http.GET
import retrofit2.http.Query

/** Retrofit binding for the MOTIS HTTP API. */
interface MotisApi {

    @GET("api/v1/geocode")
    suspend fun geocode(
        @Query("text") text: String
    ): List<GeocodeDto>

    /**
     * @param time ISO-8601 UTC timestamp (e.g. 2026-06-05T09:00:00Z).
     * @param arriveBy when true, [time] is the desired arrival time instead of departure time.
     * @param transitModes comma-separated transit modes, or empty to disable transit.
     * @param directModes comma-separated direct (non-transit) modes, or empty to disable.
     * @param maxDirectTime MOTIS defaults this to 1800s (30 min) server-side; anything
     * slower than that (e.g. a real intercity CAR/BIKE/WALK trip) is silently dropped
     * from the response's `direct` list unless raised here. Capped server-side by
     * config's `street_routing_max_direct_seconds` (21600s / 6h by default).
     */
    @GET("api/v1/plan")
    suspend fun plan(
        @Query("fromPlace") fromPlace: String,
        @Query("toPlace") toPlace: String,
        @Query("time") time: String,
        @Query("transitModes") transitModes: String,
        @Query("directModes") directModes: String,
        @Query("arriveBy") arriveBy: Boolean = false,
        @Query("maxDirectTime") maxDirectTime: Int = 21600
    ): PlanResponseDto

    /**
     * Upcoming departures near a coordinate (no stop id needed) — powers the
     * "nearby" screen. [radius] is required by MOTIS whenever [center] is used
     * instead of a `stopId`.
     */
    @GET("api/v6/stoptimes")
    suspend fun stoptimes(
        @Query("center") center: String,
        @Query("radius") radius: Int = 700,
        @Query("n") n: Int = 30,
        @Query("arriveBy") arriveBy: Boolean = false
    ): StopTimesResponseDto

    /**
     * Transit stops (bus/rail/etc.) inside a map viewport — powers the map's
     * station markers. [min]/[max] are "lat,lon" pairs (lower-right / upper-left).
     */
    @GET("api/v1/map/stops")
    suspend fun mapStops(
        @Query("min") min: String,
        @Query("max") max: String
    ): List<PlaceDto>

    /**
     * Upcoming departures at a known [stopId] — powers the map's station-tap
     * bottom sheet (and, filtered client-side to one route, its "full
     * schedule for this line" drill-down). [window] is in seconds.
     */
    @GET("api/v6/stoptimes")
    suspend fun stoptimesForStop(
        @Query("stopId") stopId: String,
        @Query("n") n: Int = 20,
        @Query("window") window: Int? = null,
        @Query("arriveBy") arriveBy: Boolean = false
    ): StopTimesResponseDto
}

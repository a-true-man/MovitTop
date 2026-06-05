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
     * @param transitModes comma-separated transit modes, or empty to disable transit.
     * @param directModes comma-separated direct (non-transit) modes, or empty to disable.
     */
    @GET("api/v1/plan")
    suspend fun plan(
        @Query("fromPlace") fromPlace: String,
        @Query("toPlace") toPlace: String,
        @Query("time") time: String,
        @Query("transitModes") transitModes: String,
        @Query("directModes") directModes: String
    ): PlanResponseDto
}

package iam699030.gmail.movitop.data

import android.graphics.Color
import iam699030.gmail.movitop.data.api.LegDto

/**
 * Picks the color a route leg is drawn in — on the map and as the step-list
 * marker dot — so a line change or a walking segment is visually obvious at a
 * glance, matching how Moovit-style apps color each leg of a trip.
 *
 * Real transit lines use their official GTFS `routeColor` when MOTIS reports
 * one; everything else (walking, biking, driving, or a transit line with no
 * configured color) falls back to a fixed or hashed color from [FALLBACK_PALETTE]
 * so it's still stable and distinguishable across a session.
 */
object LegColors {

    private const val WALK_COLOR = 0xFF8A93A6.toInt()
    private const val BIKE_COLOR = 0xFF3DBE7A.toInt()
    private const val DRIVE_COLOR = 0xFF9B59B6.toInt()

    /** A wide, mutually-distinguishable palette for transit lines with no GTFS color. */
    private val FALLBACK_PALETTE = intArrayOf(
        0xFFE63946.toInt(), 0xFFF4A300.toInt(), 0xFF2A9D8F.toInt(), 0xFF457B9D.toInt(),
        0xFF8E44AD.toInt(), 0xFFE67E22.toInt(), 0xFF16A085.toInt(), 0xFFD62839.toInt(),
        0xFF3D5A80.toInt(), 0xFFC9A227.toInt()
    )

    fun forLeg(leg: LegDto): Int = when (leg.mode) {
        "WALK" -> WALK_COLOR
        "BIKE" -> BIKE_COLOR
        "CAR" -> DRIVE_COLOR
        else -> leg.routeColor?.let(::parseHex)
            ?: fallbackFor(leg.routeShortName ?: leg.headsign ?: leg.mode.orEmpty())
    }

    /** Same fallback hashing, usable when there's no [LegDto] at hand (e.g. nearby-stop rows). */
    fun fallbackFor(key: String): Int {
        if (key.isEmpty()) return FALLBACK_PALETTE[0]
        val index = (key.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % FALLBACK_PALETTE.size
        return FALLBACK_PALETTE[index]
    }

    /** Parses a bare "RRGGBB" (GTFS `routeColor` style, no '#') or "#RRGGBB"/"#AARRGGBB". */
    private fun parseHex(hex: String): Int? {
        val cleaned = hex.trim().removePrefix("#")
        if (cleaned.isEmpty() || cleaned.length !in setOf(6, 8)) return null
        return try {
            Color.parseColor("#$cleaned")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

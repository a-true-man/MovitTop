package iam699030.gmail.movitop.data

import kotlin.math.roundToInt

/**
 * "Driver" (דרייבר) shared-ride price estimate — one-way, 4-seat vehicle,
 * rounded to the nearest 10₪. Ported from ../../../../../../../driver-pricing/driver_pricing.py
 * (see DRIVER_PRICING_FORMULA.md there for the full spec, all 4 vehicle
 * classes, and the regression provenance). Only the 4-seat one-way figure is
 * used here — that's what fits in the direct-mode card's price badge.
 *
 * Coefficients are fit constants (R²=0.986 against 145 real driverim.online
 * list prices) — keep them exactly as given, they're not defaults to round.
 */
private const val BASE_FLAT_FEE = 36.1
private const val PER_KM_RATE = 3.606
private const val MAJOR_CITY_PAIR_DISCOUNT = 0.85
private const val REMOTE_DESTINATION_SURCHARGE = 1.2
private const val MAJOR_CITY_PAIR_MIN_KM = 40.0

fun estimateDriverPrice(
    distanceKm: Double,
    isMajorCityPair: Boolean = false,
    isRemoteDestination: Boolean = false,
    roundTo: Int = 10
): Int {
    var seats4 = BASE_FLAT_FEE + PER_KM_RATE * distanceKm
    if (isMajorCityPair && distanceKm > MAJOR_CITY_PAIR_MIN_KM) {
        seats4 *= MAJOR_CITY_PAIR_DISCOUNT
    } else if (isRemoteDestination) {
        seats4 *= REMOTE_DESTINATION_SURCHARGE
    }
    return roundToNearest(seats4, roundTo)
}

private fun roundToNearest(value: Double, step: Int): Int =
    (value / step).roundToInt() * step

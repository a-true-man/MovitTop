package iam699030.gmail.movitop.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Regulated Israeli taxi-meter fare estimate, per the Ministry of
 * Transport's public tariff (הרשות הארצית לתחבורה ציבורית — תעריפי מוניות,
 * in effect from 01/04/2026): flag-fall + per-km rate (which of the 3
 * legally-defined tariffs applies depends on day/time) + the per-km
 * surcharge past 10km + the phone/radio-order addition — since calling a
 * station from this screen *is* a phone order.
 *
 * This is an estimate for display, not a metered fare — actual traffic
 * conditions, the driver's route, and exact tariff-switch timing can move
 * the real number.
 */
private const val FLAG_FALL = 12.46
private const val TARIFF_A_PER_KM = 1.95 // Sun–Thu 06:00–21:00, Fri 06:00–16:00
private const val TARIFF_B_PER_KM = 2.34 // nights, Fri afternoon, Sat daytime
private const val TARIFF_C_PER_KM = 2.73 // Fri late night, Sat night
private const val EXTRA_KM_THRESHOLD = 10.0
private const val EXTRA_KM_SURCHARGE = 1.84
private const val PHONE_ORDER_SURCHARGE = 5.78

fun estimateTaxiPrice(distanceKm: Double, atMillis: Long = System.currentTimeMillis()): Int {
    val perKmRate = currentTariffPerKm(atMillis)
    val extraKm = (distanceKm - EXTRA_KM_THRESHOLD).coerceAtLeast(0.0)
    val price = FLAG_FALL + distanceKm * perKmRate + extraKm * EXTRA_KM_SURCHARGE + PHONE_ORDER_SURCHARGE
    return price.roundToInt()
}

private fun currentTariffPerKm(atMillis: Long): Double {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
    cal.timeInMillis = atMillis
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // Calendar.SUNDAY..SATURDAY
    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    return when (dayOfWeek) {
        Calendar.FRIDAY -> when {
            minuteOfDay < at(6) -> TARIFF_B_PER_KM // still Thu night into early Fri
            minuteOfDay < at(16) -> TARIFF_A_PER_KM
            minuteOfDay < at(21) -> TARIFF_B_PER_KM
            else -> TARIFF_C_PER_KM
        }
        Calendar.SATURDAY -> when {
            minuteOfDay < at(19) -> TARIFF_B_PER_KM
            else -> TARIFF_C_PER_KM
        }
        else -> when { // Sun–Thu
            minuteOfDay < at(6) -> TARIFF_B_PER_KM
            minuteOfDay < at(21) -> TARIFF_A_PER_KM
            else -> TARIFF_B_PER_KM
        }
    }
}

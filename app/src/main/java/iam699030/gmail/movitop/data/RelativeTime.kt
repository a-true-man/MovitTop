package iam699030.gmail.movitop.data

import android.content.Context
import iam699030.gmail.movitop.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formats a future timestamp the way a transit app should, not as a raw
 * minute count: under an hour that's still "in X min", but past an hour a
 * bare number stops being intuitive (nobody pictures "in 1100 min"), so this
 * switches to an hour+minute phrase, and once the time falls on a different
 * calendar day than now, to an absolute "departs <weekday> at HH:MM" instead.
 *
 * Always computed fresh against [nowMillis] (defaults to the real "now") —
 * callers re-format live off the same stored epoch on a timer instead of
 * freezing a countdown at fetch time (see util/Ticker.kt).
 */
object RelativeTime {

    private val WEEKDAY_NAMES = intArrayOf(
        R.string.weekday_sunday, R.string.weekday_monday, R.string.weekday_tuesday,
        R.string.weekday_wednesday, R.string.weekday_thursday, R.string.weekday_friday,
        R.string.weekday_saturday
    )

    /** "Departing now", "in X min", "in 1 hour 6 min", or "Departs Sunday at 02:45". */
    fun describe(context: Context, epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diffMinutes = Math.round((epochMillis - nowMillis) / 60_000.0)
        if (diffMinutes <= 0) return context.getString(R.string.nearby_departing_now)
        if (diffMinutes < 60) return context.getString(R.string.nearby_minutes_until, diffMinutes)

        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        if (sameDay) {
            val hours = diffMinutes / 60
            val minutes = diffMinutes % 60
            val hoursText = hoursPhrase(context, hours)
            return if (minutes == 0L) {
                context.getString(R.string.relative_in_hours_only, hoursText)
            } else {
                context.getString(R.string.relative_in_hours_and_minutes, hoursText, minutes)
            }
        }

        val timeText = CLOCK_FORMAT.format(Date(epochMillis))
        val dayName = context.getString(WEEKDAY_NAMES[target.get(Calendar.DAY_OF_WEEK) - 1])
        return context.getString(R.string.relative_departs_on_day, dayName, timeText)
    }

    /** True once [epochMillis] is far enough in the past that a countdown no longer makes sense. */
    fun isStale(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - epochMillis > STALE_AFTER_MILLIS

    private fun hoursPhrase(context: Context, hours: Long): String = when (hours) {
        1L -> context.getString(R.string.relative_hour_singular)
        2L -> context.getString(R.string.relative_hour_dual)
        else -> context.getString(R.string.relative_hours_plural, hours)
    }

    private val CLOCK_FORMAT get() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private const val STALE_AFTER_MILLIS = 60_000L
}

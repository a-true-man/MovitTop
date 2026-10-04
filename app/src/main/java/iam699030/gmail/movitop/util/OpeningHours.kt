package iam699030.gmail.movitop.util

import java.util.Calendar

/**
 * Shared "is it open right now" check for anything stored as a 7-element
 * Sun..Sat `hoursByDay` list (each entry `"HH:MM-HH:MM"` or "" for unknown),
 * the shape both [iam699030.gmail.movitop.data.RavKavStation] and
 * [iam699030.gmail.movitop.data.Poi] use. Returns null — never a guess —
 * when that day's entry is blank, so callers can tell "confirmed closed"
 * apart from "we don't know".
 */
fun isOpenNow(hoursByDay: List<String>, now: Calendar = Calendar.getInstance()): Boolean? {
    if (hoursByDay.size != 7) return null
    // Calendar.DAY_OF_WEEK is SUNDAY=1..SATURDAY=7; hoursByDay is Sun..Sat (0..6).
    val todayIndex = now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val today = hoursByDay[todayIndex]
    if (today.isBlank()) return null

    val (startText, endText) = today.split("-", limit = 2).takeIf { it.size == 2 } ?: return null
    val startMinutes = parseHhMm(startText) ?: return null
    val endMinutes = parseHhMm(endText) ?: return null
    val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    // endMinutes == 24:00 represented as 1440; a plain "overnight past midnight"
    // range (e.g. 22:00-02:00) isn't supported by this single-field shape.
    return nowMinutes in startMinutes until endMinutes
}

private fun parseHhMm(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    return hours * 60 + minutes
}

package iam699030.gmail.movitop.data

/**
 * Israel's MOT GTFS feed's trip_headsign field routinely concatenates a city
 * and a stop/neighborhood name with a literal underscore (e.g.
 * "בת ים_הקוממיות", "חיפה_מרכזית חוף הכרמל") instead of a space — seen both
 * via the bundled offline schedule DB (build_line_schedules.py stores
 * trip_headsign as-is) and live through MOTIS's own API (which passes the
 * same feed's headsign through unchanged). Clean it up wherever it reaches
 * the UI, instead of leaking the feed's internal separator to the user.
 */
object GtfsHeadsign {
    fun clean(raw: String?): String =
        raw?.replace('_', ' ')?.trim().orEmpty()
}

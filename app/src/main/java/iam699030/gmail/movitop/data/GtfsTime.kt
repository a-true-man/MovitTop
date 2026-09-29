package iam699030.gmail.movitop.data

/** GTFS allows "25:10:00" for past-midnight trips — normalize e.g. to "01:10" for display. */
object GtfsTime {
    fun format(gtfsTime: String): String {
        val parts = gtfsTime.split(":")
        if (parts.size < 2) return gtfsTime
        val hour = parts[0].toIntOrNull() ?: return gtfsTime
        return "%02d:%s".format(hour % 24, parts[1])
    }
}

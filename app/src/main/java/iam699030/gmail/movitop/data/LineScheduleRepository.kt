package iam699030.gmail.movitop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class LineDeparture(
    val routeShortName: String,
    val routeLongName: String,
    val agencyName: String,
    val headsign: String,
    val firstStopName: String,
    val departureTime: String // "HH:MM:SS", GTFS allows >24:00:00 for past-midnight trips
)

/**
 * Reads the small offline SQLite DB built by build_line_schedules.py (one
 * row per trip's first-stop departure time + a calendar table) for the
 * "Line times" screen — a search-a-line-see-its-schedule feature that's a
 * plain local lookup, not a routing query, so it doesn't touch the MOTIS
 * engine at all.
 */
class LineScheduleRepository(private val context: Context) {

    private val dbFile: File
        get() = File(context.getExternalFilesDir(null), "motis_data/data/line_schedules.sqlite")

    fun isAvailable(): Boolean = dbFile.isFile

    /** Departures for lines whose short name matches [query], active on [dateEpochDay], sorted by time. */
    fun findDepartures(query: String, dateEpochDay: Long): List<LineDeparture> {
        if (!isAvailable() || query.isBlank()) return emptyList()

        val date = Date(dateEpochDay)
        val dayColumn = dayColumnFor(date)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)

        // A JOIN, not a pre-fetched service_id IN (...) list — Israel's national
        // GTFS calendar has thousands of service_ids active on any given day,
        // which blew past SQLite's bound-variable limit (SQLiteException:
        // "too many SQL variables") when they were all passed as placeholders.
        return try {
            queryDepartures(query, dayColumn, dateStr)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Line schedule query failed", e)
            emptyList()
        }
    }

    private fun queryDepartures(query: String, dayColumn: String, dateStr: String): List<LineDeparture> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            val results = mutableListOf<LineDeparture>()
            it.rawQuery(
                """
                SELECT d.route_short_name, d.route_long_name, d.agency_name,
                       d.headsign, d.first_stop_name, d.departure_time
                FROM departures d
                JOIN calendar c ON c.service_id = d.service_id
                WHERE d.route_short_name LIKE ?
                  AND c.$dayColumn = '1'
                  AND c.start_date <= ? AND c.end_date >= ?
                ORDER BY d.departure_time
                """.trimIndent(),
                arrayOf("%$query%", dateStr, dateStr)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results += LineDeparture(
                        routeShortName = cursor.getString(0) ?: "",
                        routeLongName = cursor.getString(1) ?: "",
                        agencyName = cursor.getString(2) ?: "",
                        headsign = cursor.getString(3) ?: "",
                        firstStopName = cursor.getString(4) ?: "",
                        departureTime = cursor.getString(5) ?: ""
                    )
                }
            }
            results
        }
    }

    private fun dayColumnFor(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "sunday"
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            else -> "saturday"
        }
    }

    companion object {
        private const val TAG = "LineScheduleRepository"
    }
}

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
    val tripId: String,
    val routeShortName: String,
    val routeLongName: String,
    val agencyName: String,
    val headsign: String,
    val departureTime: String // "HH:MM:SS", GTFS allows >24:00:00 for past-midnight trips
)

/**
 * One real-world line, its two directions already merged (see
 * build_line_schedules.py's line_group_id/paired_route_id pairing — needed
 * because route short names repeat across unrelated lines in Israel's GTFS).
 * [routeId] is the direction currently described by [firstStopName]/
 * [lastStopName]; [pairedRouteId] is the reverse direction's own route_id,
 * null if no reverse direction was found in the feed.
 */
data class LineSummary(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val agencyName: String,
    val firstStopName: String,
    val lastStopName: String,
    val pairedRouteId: String?
)

data class TripStop(
    val stopName: String,
    val arrivalTime: String
)

/**
 * Reads the small offline SQLite DB built by build_line_schedules.py for the
 * "Line times" screen — a search-a-line-see-its-schedule feature that's a
 * plain local lookup, not a routing query, so it doesn't touch the MOTIS
 * engine at all.
 */
class LineScheduleRepository(private val context: Context) {

    private val dbFile: File
        get() = File(context.getExternalFilesDir(null), "motis_data/data/line_schedules.sqlite")

    fun isAvailable(): Boolean = dbFile.isFile

    /** Distinct lines (both directions merged) whose short name contains [query]. No date filter — this answers "does this line exist", not "does it run today". */
    fun findLineSummaries(query: String): List<LineSummary> {
        if (!isAvailable() || query.isBlank()) return emptyList()
        return try {
            // AND route_id = line_group_id picks one representative row per
            // merged line — without it, a paired line's two directions would
            // each show up as their own card.
            queryLineSummaries("route_short_name LIKE ? AND route_id = line_group_id", "%${query.trim()}%")
        } catch (e: SQLiteException) {
            Log.e(TAG, "Line summary search failed", e)
            emptyList()
        }
    }

    /** Same as [findLineSummaries] but an exact short-name match, for the saved-lines list. */
    fun findLineSummariesExact(shortName: String): List<LineSummary> {
        if (!isAvailable() || shortName.isBlank()) return emptyList()
        return try {
            queryLineSummaries("route_short_name = ? AND route_id = line_group_id", shortName)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Exact line summary lookup failed", e)
            emptyList()
        }
    }

    /**
     * The line summary for one specific direction's route_id — used when the
     * user flips direction. Deliberately does NOT restrict to
     * route_id = line_group_id like the search queries above: the paired
     * (non-representative) direction is exactly what this looks up.
     */
    fun findRouteInfo(routeId: String): LineSummary? {
        if (!isAvailable()) return null
        return try {
            queryLineSummaries("route_id = ?", routeId).firstOrNull()
        } catch (e: SQLiteException) {
            Log.e(TAG, "Route info lookup failed", e)
            null
        }
    }

    private fun queryLineSummaries(whereClause: String, whereArg: String): List<LineSummary> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            val results = mutableListOf<LineSummary>()
            it.rawQuery(
                """
                SELECT DISTINCT route_id, route_short_name, route_long_name, agency_name,
                       first_stop_name, last_stop_name, paired_route_id
                FROM departures
                WHERE $whereClause
                ORDER BY route_short_name
                """.trimIndent(),
                arrayOf(whereArg)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results += LineSummary(
                        routeId = cursor.getString(0) ?: "",
                        routeShortName = cursor.getString(1) ?: "",
                        routeLongName = cursor.getString(2) ?: "",
                        agencyName = cursor.getString(3) ?: "",
                        firstStopName = cursor.getString(4) ?: "",
                        lastStopName = cursor.getString(5) ?: "",
                        pairedRouteId = cursor.getString(6)
                    )
                }
            }
            results
        }
    }

    /** Departures for one specific direction (route_id), active on [dateEpochDay], sorted by time. */
    fun findDeparturesForRoute(routeId: String, dateEpochDay: Long): List<LineDeparture> {
        if (!isAvailable()) return emptyList()
        val date = Date(dateEpochDay)
        val dayColumn = dayColumnFor(date)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.use {
                val results = mutableListOf<LineDeparture>()
                it.rawQuery(
                    """
                    SELECT d.trip_id, d.route_short_name, d.route_long_name, d.agency_name,
                           d.headsign, d.departure_time
                    FROM departures d
                    JOIN calendar c ON c.service_id = d.service_id
                    WHERE d.route_id = ?
                      AND c.$dayColumn = '1'
                      AND c.start_date <= ? AND c.end_date >= ?
                    ORDER BY d.departure_time
                    """.trimIndent(),
                    arrayOf(routeId, dateStr, dateStr)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        results += LineDeparture(
                            tripId = cursor.getString(0) ?: "",
                            routeShortName = cursor.getString(1) ?: "",
                            routeLongName = cursor.getString(2) ?: "",
                            agencyName = cursor.getString(3) ?: "",
                            headsign = cursor.getString(4) ?: "",
                            departureTime = cursor.getString(5) ?: ""
                        )
                    }
                }
                results
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Line schedule query failed", e)
            emptyList()
        }
    }

    /** One trip's full stop-by-stop list, in order, each with its arrival time. */
    fun findTripStops(tripId: String): List<TripStop> {
        if (!isAvailable() || tripId.isBlank()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.use {
                val results = mutableListOf<TripStop>()
                it.rawQuery(
                    """
                    SELECT s.stop_name, st.arrival_time
                    FROM stop_times st
                    JOIN stops s ON s.stop_id = st.stop_id
                    WHERE st.trip_row_id = (SELECT id FROM departures WHERE trip_id = ?)
                    ORDER BY st.stop_sequence
                    """.trimIndent(),
                    arrayOf(tripId)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        results += TripStop(
                            stopName = cursor.getString(0) ?: "",
                            arrivalTime = cursor.getString(1) ?: ""
                        )
                    }
                }
                results
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Trip stops query failed", e)
            emptyList()
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

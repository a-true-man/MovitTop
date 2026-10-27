package iam699030.gmail.movitop.data

import android.content.Context
import android.database.Cursor
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
 * The stop matching a searched station number (GTFS stop_code — the number
 * printed on the physical sign), with every line (both directions merged)
 * that serves it. The by-stop-number counterpart to searching a line
 * directly by its own number/name (see [LineScheduleRepository.findLineSummaries]).
 */
data class StopLines(
    val stopCode: String,
    val stopName: String,
    val lines: List<LineSummary>
)

/**
 * Reads the small offline SQLite DB built by build_line_schedules.py for the
 * "Line times" screen — a search-a-line-see-its-schedule feature that's a
 * plain local lookup, not a routing query, so it doesn't touch the MOTIS
 * engine at all.
 *
 * Schema is normalized: route-level fields (short/long name, agency,
 * terminal stops, direction pairing) live once per route_id in `routes`,
 * joined in rather than repeated on every one of that route's rows in
 * `departures` like the pre-normalization schema did. Times are stored as
 * INTEGER seconds-since-midnight rather than "HH:MM:SS" TEXT; [secondsToGtfsTime]
 * converts back to that same text format so every type in this file keeps
 * its original shape for callers (see [LineDeparture.departureTime]).
 */
class LineScheduleRepository(private val context: Context) {

    // Internal storage, not getExternalFilesDir() — see the comment on
    // MotisForegroundService.motisDataDir(); SQLite's own locking/journaling
    // is also known to misbehave and run slowly on FUSE-backed storage.
    private val dbFile: File
        get() = File(context.filesDir, "motis_data/data/line_schedules.sqlite")

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

    /**
     * The stop whose station number (GTFS stop_code — the number on the
     * physical sign) is exactly [stopCode], with every line serving it. Null
     * when no stop in the feed has that code. Looked up against
     * build_line_schedules.py's precomputed `stop_routes` table (one row per
     * distinct stop/merged-line pair) rather than scanning the much larger
     * `stop_times` table directly.
     */
    fun findLinesAtStopCode(stopCode: String): StopLines? {
        if (!isAvailable() || stopCode.isBlank()) return null
        val trimmed = stopCode.trim()
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            db.use {
                val stopName = it.rawQuery(
                    "SELECT stop_name FROM stops WHERE stop_code = ? LIMIT 1",
                    arrayOf(trimmed)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null

                val lines = mutableListOf<LineSummary>()
                it.rawQuery(
                    """
                    SELECT DISTINCT r.route_id, r.route_short_name, r.route_long_name, r.agency_name,
                           s1.stop_name, s2.stop_name, r.paired_route_id
                    FROM stops st
                    JOIN stop_routes sr ON sr.stop_id = st.stop_id
                    JOIN routes r ON r.route_id = sr.route_id
                    LEFT JOIN stops s1 ON s1.stop_id = r.first_stop_id
                    LEFT JOIN stops s2 ON s2.stop_id = r.last_stop_id
                    WHERE st.stop_code = ?
                    ORDER BY r.route_short_name
                    """.trimIndent(),
                    arrayOf(trimmed)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        lines += cursor.toLineSummary()
                    }
                }
                StopLines(stopCode = trimmed, stopName = stopName, lines = lines)
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Stop-code line lookup failed", e)
            null
        }
    }

    private fun queryLineSummaries(whereClause: String, whereArg: String): List<LineSummary> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            val results = mutableListOf<LineSummary>()
            it.rawQuery(
                """
                SELECT r.route_id, r.route_short_name, r.route_long_name, r.agency_name,
                       s1.stop_name, s2.stop_name, r.paired_route_id
                FROM routes r
                LEFT JOIN stops s1 ON s1.stop_id = r.first_stop_id
                LEFT JOIN stops s2 ON s2.stop_id = r.last_stop_id
                WHERE $whereClause
                ORDER BY r.route_short_name
                """.trimIndent(),
                arrayOf(whereArg)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results += cursor.toLineSummary()
                }
            }
            results
        }
    }

    /**
     * Reads one row shaped like `queryLineSummaries`'/[findLinesAtStopCode]'s
     * SELECT — same six columns (route_id, route_short_name, route_long_name,
     * agency_name, first stop name, last stop name) plus paired_route_id.
     */
    private fun Cursor.toLineSummary() = LineSummary(
        routeId = getString(0) ?: "",
        routeShortName = getString(1) ?: "",
        routeLongName = getString(2) ?: "",
        agencyName = getString(3) ?: "",
        firstStopName = getString(4) ?: "",
        lastStopName = getString(5) ?: "",
        pairedRouteId = getString(6)
    )

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
                    SELECT d.trip_id, r.route_short_name, r.route_long_name, r.agency_name,
                           d.headsign, d.departure_time
                    FROM departures d
                    JOIN routes r ON r.route_id = d.route_id
                    JOIN calendar c ON c.service_id = d.service_id
                    WHERE d.route_id = ?
                      AND c.$dayColumn = 1
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
                            headsign = GtfsHeadsign.clean(cursor.getString(4)),
                            departureTime = secondsToGtfsTime(cursor.getLong(5))
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
                            arrivalTime = secondsToGtfsTime(cursor.getLong(1))
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

    /** Inverse of build_line_schedules.py's seconds encoding — GTFS allows hour >= 24 for past-midnight trips, same as the TEXT format this replaces. */
    private fun secondsToGtfsTime(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
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

package iam699030.gmail.movitop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One past origin→destination search, auto-recorded (see [TripHistoryRepository]). */
data class TripHistoryEntry(
    val originName: String,
    val originLat: Double?,
    val originLon: Double?,
    val destinationName: String,
    val destinationLat: Double?,
    val destinationLon: Double?,
    val lastUsedEpochMillis: Long,
    val pinned: Boolean
) {
    val originCoord: GeoPoint? get() = originLat?.let { lat -> originLon?.let { lon -> GeoPoint(lat, lon) } }
    val destinationCoord: GeoPoint? get() = destinationLat?.let { lat -> destinationLon?.let { lon -> GeoPoint(lat, lon) } }

    /** Identifies "the same trip" regardless of when it was last used or whether it's pinned. */
    val key: String get() = "$originName>$destinationName"
}

/**
 * Remembers every origin→destination trip the user has actually searched —
 * "recent trips" — the way [RecentPlacesRepository] remembers individual
 * places. A trip can additionally be pinned so it survives the unpinned
 * cap indefinitely, mirroring [PinnedPlacesRepository]'s Home/Work slots but
 * for a whole trip instead of a single place.
 */
class TripHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<TripHistoryEntry> {
        val raw = prefs.getString(KEY_TRIPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toEntry() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves this origin/destination pair to the front (or inserts it), preserving its pinned flag. */
    fun record(
        originName: String,
        originCoord: GeoPoint?,
        destinationName: String,
        destinationCoord: GeoPoint?
    ) {
        val now = System.currentTimeMillis()
        val existing = getAll()
        val newKey = "$originName>$destinationName"
        val wasPinned = existing.firstOrNull { it.key == newKey }?.pinned ?: false
        val entry = TripHistoryEntry(
            originName, originCoord?.lat, originCoord?.lon,
            destinationName, destinationCoord?.lat, destinationCoord?.lon,
            now, wasPinned
        )
        val updated = listOf(entry) + existing.filterNot { it.key == newKey }
        save(trimUnpinned(updated))
    }

    fun togglePin(entry: TripHistoryEntry) {
        val updated = getAll().map {
            if (it.key == entry.key) it.copy(pinned = !it.pinned) else it
        }
        save(updated)
    }

    /** Pinned first (most-recently-used first within that group), then recent, capped. */
    private fun trimUnpinned(entries: List<TripHistoryEntry>): List<TripHistoryEntry> {
        val pinned = entries.filter { it.pinned }
        val unpinned = entries.filterNot { it.pinned }.take(MAX_UNPINNED)
        return pinned + unpinned
    }

    private fun save(entries: List<TripHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("originName", entry.originName)
                    put("originLat", entry.originLat ?: JSONObject.NULL)
                    put("originLon", entry.originLon ?: JSONObject.NULL)
                    put("destinationName", entry.destinationName)
                    put("destinationLat", entry.destinationLat ?: JSONObject.NULL)
                    put("destinationLon", entry.destinationLon ?: JSONObject.NULL)
                    put("lastUsedEpochMillis", entry.lastUsedEpochMillis)
                    put("pinned", entry.pinned)
                }
            )
        }
        prefs.edit().putString(KEY_TRIPS, array.toString()).apply()
    }

    private fun JSONObject.toEntry(): TripHistoryEntry? {
        val originName = optString("originName").takeIf { it.isNotBlank() } ?: return null
        val destinationName = optString("destinationName").takeIf { it.isNotBlank() } ?: return null
        return TripHistoryEntry(
            originName = originName,
            originLat = if (isNull("originLat")) null else optDouble("originLat"),
            originLon = if (isNull("originLon")) null else optDouble("originLon"),
            destinationName = destinationName,
            destinationLat = if (isNull("destinationLat")) null else optDouble("destinationLat"),
            destinationLon = if (isNull("destinationLon")) null else optDouble("destinationLon"),
            lastUsedEpochMillis = optLong("lastUsedEpochMillis"),
            pinned = optBoolean("pinned", false)
        )
    }

    companion object {
        private const val PREFS_NAME = "movitop_trip_history"
        private const val KEY_TRIPS = "trips"
        private const val MAX_UNPINNED = 8
    }
}

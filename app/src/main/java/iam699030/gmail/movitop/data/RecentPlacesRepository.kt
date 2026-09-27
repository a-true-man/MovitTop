package iam699030.gmail.movitop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the last few places the user actually picked (origin or
 * destination), most recent first — shown in the search screen before the
 * user types anything, the way Moovit surfaces recent/favorite places.
 */
class RecentPlacesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<GeocodePlace> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GeocodePlace(
                    name = name,
                    subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() },
                    lat = obj.optDouble("lat", 0.0),
                    lon = obj.optDouble("lon", 0.0),
                    id = obj.optString("id").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves [place] to the front, dedupes by [GeocodePlace.displayKey], caps at [MAX_RECENTS]. */
    fun record(place: GeocodePlace) {
        val updated = listOf(place) + getAll().filterNot { it.displayKey == place.displayKey }
        save(updated.take(MAX_RECENTS))
    }

    private fun save(places: List<GeocodePlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject().apply {
                    put("name", place.name)
                    put("subtitle", place.subtitle.orEmpty())
                    put("lat", place.lat)
                    put("lon", place.lon)
                    put("id", place.id.orEmpty())
                }
            )
        }
        prefs.edit().putString(KEY_PLACES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "movitop_recent_places"
        private const val KEY_PLACES = "recent_places"
        private const val MAX_RECENTS = 8
    }
}

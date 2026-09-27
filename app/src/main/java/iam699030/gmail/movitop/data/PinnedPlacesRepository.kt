package iam699030.gmail.movitop.data

import android.content.Context
import org.json.JSONObject

/** Which pinned slot a place is being saved into (see [PinnedPlacesRepository]). */
enum class PinnedSlot(val key: String) { HOME("home"), WORK("work") }

/**
 * Persists two named quick-pick places — home and work — the way Moovit lets
 * you tap straight to either instead of searching every time.
 */
class PinnedPlacesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(slot: PinnedSlot): GeocodePlace? {
        val raw = prefs.getString(slot.key, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            GeocodePlace(
                name = name,
                subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() },
                lat = obj.optDouble("lat", 0.0),
                lon = obj.optDouble("lon", 0.0)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun set(slot: PinnedSlot, place: GeocodePlace) {
        val obj = JSONObject().apply {
            put("name", place.name)
            put("subtitle", place.subtitle.orEmpty())
            put("lat", place.lat)
            put("lon", place.lon)
        }
        prefs.edit().putString(slot.key, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "movitop_pinned_places"
    }
}

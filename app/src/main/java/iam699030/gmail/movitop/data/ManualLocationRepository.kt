package iam699030.gmail.movitop.data

import android.content.Context
import org.json.JSONObject

/**
 * A manually-entered address the user sets in Settings as a stand-in for
 * "my current location" when a satellite GPS fix can't be obtained in time
 * (see [iam699030.gmail.movitop.nav.LocationTracker] — this app never falls
 * back to network/fused location, so indoors or under a weak sky view a raw
 * GPS fix can simply never arrive). Consumed by MainActivity's "center on my
 * location" and SearchLocationActivity's "my current location" row once
 * their GPS one-shot lookup times out.
 */
class ManualLocationRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): GeocodePlace? {
        val raw = prefs.getString(KEY_PLACE, null) ?: return null
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

    fun set(place: GeocodePlace) {
        val obj = JSONObject().apply {
            put("name", place.name)
            put("subtitle", place.subtitle.orEmpty())
            put("lat", place.lat)
            put("lon", place.lon)
        }
        prefs.edit().putString(KEY_PLACE, obj.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PLACE).apply()
    }

    companion object {
        private const val PREFS_NAME = "movitop_manual_location"
        private const val KEY_PLACE = "place"
    }
}

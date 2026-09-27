package iam699030.gmail.movitop.data

import android.content.Context
import org.json.JSONArray

/**
 * Persists the user's saved/starred line short names (e.g. "480") so the
 * "Line times" screen can show them by default instead of starting blank —
 * Moovit-style saved lines. Keyed by route short name only: this is a plain
 * on-device favorites list, not tied to a specific agency or direction.
 */
class FavoriteLinesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<String> {
        val raw = prefs.getString(KEY_LINES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isFavorite(routeShortName: String): Boolean = routeShortName in getAll()

    /** Adds/removes [routeShortName] and returns the new favorite state. */
    fun toggle(routeShortName: String): Boolean {
        val current = getAll().toMutableList()
        val nowFavorite = if (current.remove(routeShortName)) {
            false
        } else {
            current += routeShortName
            true
        }
        save(current)
        return nowFavorite
    }

    private fun save(lines: List<String>) {
        val array = JSONArray()
        lines.forEach { array.put(it) }
        prefs.edit().putString(KEY_LINES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "movitop_favorites"
        private const val KEY_LINES = "favorite_lines"
    }
}

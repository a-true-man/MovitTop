package iam699030.gmail.movitop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the bundled POI dataset (`assets/osm_pois.tsv`, extracted offline
 * from OpenStreetMap by extract_osm_pois.py — see its docstring) once into
 * memory, then serves cheap in-memory viewport filters — ~24k rows, a linear
 * scan is well under a millisecond, same reasoning as [RavKavStationsRepository].
 */
class PoiRepository(private val context: Context) {

    @Volatile
    private var cache: List<Poi>? = null

    suspend fun poisInBounds(southWest: GeoPoint, northEast: GeoPoint): List<Poi> =
        allPois().filter { poi ->
            poi.point.lat in southWest.lat..northEast.lat &&
                poi.point.lon in southWest.lon..northEast.lon
        }

    private suspend fun allPois(): List<Poi> =
        cache ?: withContext(Dispatchers.IO) { cache ?: load().also { cache = it } }

    private fun load(): List<Poi> {
        val result = mutableListOf<Poi>()
        context.assets.open(ASSET_NAME).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = line.split("\t")
                if (f.size < 7) return@forEachLine
                val category = PoiCategory.fromKey(f[1]) ?: return@forEachLine
                val lat = f[5].toDoubleOrNull() ?: return@forEachLine
                val lon = f[6].toDoubleOrNull() ?: return@forEachLine
                val hours = f[4].split("|")
                result += Poi(
                    name = f[0],
                    category = category,
                    subtype = f[2],
                    address = f[3],
                    hoursByDay = if (hours.size == 7) hours else List(7) { "" },
                    point = GeoPoint(lat, lon)
                )
            }
        }
        return result
    }

    companion object {
        private const val ASSET_NAME = "osm_pois.tsv"
    }
}

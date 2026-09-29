package iam699030.gmail.movitop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** One Rav-Kav card loading/reload point (from gtfs.mot.gov.il's ChargingRavKav dataset). */
data class RavKavStation(
    val name: String,
    val city: String,
    val address: String,
    val agency: String,
    val phone: String,
    /** Sun..Sat, each "HH:MM-HH:MM" or "" when unknown/closed. */
    val hoursByDay: List<String>,
    val acceptsCash: Boolean,
    val acceptsCreditCard: Boolean,
    val accessible: Boolean,
    val point: GeoPoint
)

/**
 * Loads the bundled Rav-Kav charging-station dataset
 * (`assets/ravkav_stations.tsv`, merged offline from gtfs.mot.gov.il's
 * ChargingRavKav.zip) once into memory, then serves cheap in-memory viewport
 * filters — ~11k rows, a linear scan is well under a millisecond, so no
 * on-device index is worth the complexity.
 */
class RavKavStationsRepository(private val context: Context) {

    @Volatile
    private var cache: List<RavKavStation>? = null

    suspend fun stationsInBounds(southWest: GeoPoint, northEast: GeoPoint): List<RavKavStation> =
        allStations().filter { s ->
            s.point.lat in southWest.lat..northEast.lat &&
                s.point.lon in southWest.lon..northEast.lon
        }

    private suspend fun allStations(): List<RavKavStation> =
        cache ?: withContext(Dispatchers.IO) { cache ?: load().also { cache = it } }

    private fun load(): List<RavKavStation> {
        val result = mutableListOf<RavKavStation>()
        context.assets.open(ASSET_NAME).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = line.split("\t")
                if (f.size < 11) return@forEachLine
                val lat = f[9].toDoubleOrNull() ?: return@forEachLine
                val lon = f[10].toDoubleOrNull() ?: return@forEachLine
                result += RavKavStation(
                    name = f[0],
                    city = f[1],
                    address = f[2],
                    agency = f[3],
                    phone = f[4],
                    hoursByDay = f[5].split("|"),
                    acceptsCash = f[6] == "1",
                    acceptsCreditCard = f[7] == "1",
                    accessible = f[8] == "1",
                    point = GeoPoint(lat, lon)
                )
            }
        }
        return result
    }

    companion object {
        private const val ASSET_NAME = "ravkav_stations.tsv"
    }
}

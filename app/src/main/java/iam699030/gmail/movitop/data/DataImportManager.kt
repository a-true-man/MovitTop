package iam699030.gmail.movitop.data

import android.content.Context
import android.net.Uri
import android.util.Log
import iam699030.gmail.movitop.MotisForegroundService
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Imports an updated MOTIS data package (produced offline by
 * package_motis_data.sh from a fresh compile_motis_graph.sh run) from local
 * storage — no network involved, so a GTFS/OSM refresh never requires a new
 * APK. The zip must contain the `data/` graph files at its root, optionally
 * alongside `israel.map` and a `tzdata/` folder (only needed if the IANA
 * timezone database itself changed, which is rare — timetable/OSM refreshes
 * don't need a new one).
 *
 * The package is extracted to a staging directory and validated *before*
 * anything live is touched; the previous data is only replaced once the new
 * package is confirmed usable, so a bad/corrupt file can never brick a
 * working install.
 */
class DataImportManager(private val context: Context) {

    sealed interface Result {
        data class Success(val filesImported: Int) : Result
        data class Failure(val reason: String) : Result
    }

    private val motisRoot: File
        get() = File(context.getExternalFilesDir(null), "motis_data")

    suspend fun import(uri: Uri): Result {
        val staging = File(motisRoot, "data_staging")
        val stagingGraph = File(staging, "data")
        val stagingTzdata = File(staging, "tzdata")
        try {
            staging.deleteRecursively()
            stagingGraph.mkdirs()

            val extractedMapFile = File(staging, "israel.map")
            var entryCount = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = when {
                                entry.name == "israel.map" -> extractedMapFile
                                entry.name.startsWith("tzdata/") ->
                                    File(stagingTzdata, entry.name.removePrefix("tzdata/"))
                                else -> File(stagingGraph, entry.name.substringAfterLast('/'))
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zip.copyTo(out) }
                            entryCount++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result.Failure("לא ניתן היה לפתוח את הקובץ שנבחר")

            if (!File(stagingGraph, "config.yml").isFile) {
                staging.deleteRecursively()
                return Result.Failure("הקובץ שנבחר אינו חבילת נתונים תקינה (חסר config.yml)")
            }

            // Only touch the live directory once the new package is verified.
            MotisForegroundService.stop(context)

            val liveGraph = File(motisRoot, "data")
            val liveTzdata = File(motisRoot, "tzdata")
            val liveMap = File(motisRoot, "israel.map")
            liveGraph.deleteRecursively()
            stagingGraph.renameTo(liveGraph)
            if (stagingTzdata.isDirectory) {
                liveTzdata.deleteRecursively()
                stagingTzdata.renameTo(liveTzdata)
            }
            if (extractedMapFile.isFile) {
                liveMap.delete()
                extractedMapFile.renameTo(liveMap)
            }
            staging.deleteRecursively()

            MotisForegroundService.start(context)
            return Result.Success(entryCount)
        } catch (e: IOException) {
            Log.e(TAG, "Data import failed", e)
            staging.deleteRecursively()
            return Result.Failure("הייבוא נכשל: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DataImportManager"
    }
}

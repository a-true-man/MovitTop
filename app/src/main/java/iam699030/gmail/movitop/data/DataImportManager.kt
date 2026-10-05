package iam699030.gmail.movitop.data

import android.content.Context
import android.net.Uri
import android.util.Log
import iam699030.gmail.movitop.MotisForegroundService
import iam699030.gmail.movitop.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Internal storage, not getExternalFilesDir() — see the comment on
    // MotisForegroundService.motisDataDir(), the canonical definition of this
    // same path; external storage's FUSE layer is badly suited to this data.
    private val motisRoot: File
        get() = File(context.filesDir, "motis_data")

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        importBlocking(uri)
    }

    private fun importBlocking(uri: Uri): Result {
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
                            // Preserve the entry's subdirectory structure (e.g. adr/t.bin,
                            // osr/rtree_data.bin) — newer compiled graphs organize files into
                            // per-module subfolders, and flattening to just the basename
                            // silently collides same-named files from different subfolders
                            // (adr/rtree_data.bin vs osr/rtree_data.bin) and puts files where
                            // the MOTIS binary can't find them.
                            val target = when {
                                entry.name == "israel.map" -> extractedMapFile
                                entry.name.startsWith("tzdata/") ->
                                    File(stagingTzdata, entry.name.removePrefix("tzdata/"))
                                else -> File(stagingGraph, entry.name)
                            }
                            val targetDir = if (entry.name.startsWith("tzdata/")) stagingTzdata else stagingGraph
                            if (target != extractedMapFile &&
                                !target.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
                            ) {
                                throw IOException("Zip entry escapes target directory: ${entry.name}")
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zip.copyTo(out) }
                            entryCount++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result.Failure(context.getString(R.string.data_import_error_open_failed))

            if (!File(stagingGraph, "config.yml").isFile) {
                staging.deleteRecursively()
                return Result.Failure(context.getString(R.string.data_import_error_invalid_package))
            }

            // Only touch the live directory once the new package is verified.
            MotisForegroundService.stop(context)

            val liveGraph = File(motisRoot, "data")
            val liveTzdata = File(motisRoot, "tzdata")
            val liveMap = File(motisRoot, "israel.map")
            if (liveGraph.exists() && !liveGraph.deleteRecursively()) {
                throw IOException("Could not remove the previous graph at ${liveGraph.absolutePath}")
            }
            if (!stagingGraph.renameTo(liveGraph)) {
                throw IOException("Could not move the new graph into place at ${liveGraph.absolutePath}")
            }
            if (stagingTzdata.isDirectory) {
                if (liveTzdata.exists() && !liveTzdata.deleteRecursively()) {
                    throw IOException("Could not remove the previous tzdata at ${liveTzdata.absolutePath}")
                }
                if (!stagingTzdata.renameTo(liveTzdata)) {
                    throw IOException("Could not move the new tzdata into place at ${liveTzdata.absolutePath}")
                }
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
            return Result.Failure(e.message ?: context.getString(R.string.data_import_error_unknown))
        }
    }

    companion object {
        private const val TAG = "DataImportManager"
    }
}

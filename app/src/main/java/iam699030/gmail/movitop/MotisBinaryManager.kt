package iam699030.gmail.movitop

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Extracts the Linux ARM64 MOTIS binary from assets and runs it as a child
 * process. Android's kernel can execute statically linked Linux ELF binaries on
 * arm64-v8a devices without JNI.
 */
class MotisBinaryManager(private val context: Context) {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logThread: Thread? = null

    /** Absolute path to the extracted, executable binary in [Context.filesDir]. */
    val binaryPath: File
        get() = File(context.filesDir, BINARY_NAME)

    /**
     * Copies [BINARY_NAME] from assets (once) and marks it executable.
     * @return the path to the ready-to-run binary.
     */
    fun ensureBinary(): File {
        val out = binaryPath
        if (out.exists() && out.length() > 0L) {
            makeExecutable(out)
            return out
        }

        context.assets.open(BINARY_NAME).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        makeExecutable(out)
        Log.i(TAG, "Extracted $BINARY_NAME (${out.length()} bytes) -> ${out.absolutePath}")
        return out
    }

    /**
     * Starts `./motis-server server -d data` in [dataRoot], where [dataRoot]
     * contains the compiled `data/` graph (with its own `config.yml`).
     *
     * @return the running [Process], or null if already running / launch failed.
     */
    fun startProcess(dataRoot: File): Process? {
        if (process?.isAlive == true) {
            Log.i(TAG, "MOTIS process already running")
            return process
        }

        val graphDir = File(dataRoot, "data")
        if (!File(graphDir, "config.yml").isFile) {
            Log.e(TAG, "Missing ${graphDir.absolutePath}/config.yml — push graph first (PUSH_GRAPH=1).")
            return null
        }

        return try {
            val binary = ensureBinary()
            val pb = ProcessBuilder(
                binary.absolutePath,
                "server",
                "-d",
                "data"
            ).apply {
                directory(dataRoot)
                redirectErrorStream(true)
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }

            val proc = pb.start()
            process = proc
            logThread = thread(name = "motis-logcat", isDaemon = true) {
                streamToLogcat(proc)
            }
            Log.i(TAG, "Started MOTIS cwd=${dataRoot.absolutePath}")
            proc
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MOTIS process", e)
            null
        }
    }

    /** Sends SIGTERM to the child process and clears the handle. */
    fun stopProcess() {
        val proc = process ?: return
        try {
            if (proc.isAlive) {
                proc.destroy()
                proc.waitFor()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MOTIS process", e)
        } finally {
            process = null
            logThread = null
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun streamToLogcat(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, line!!)
                }
            }
            val code = proc.waitFor()
            Log.i(TAG, "MOTIS process exited with code $code")
        } catch (e: Exception) {
            if (proc.isAlive) {
                Log.w(TAG, "Log reader interrupted", e)
            }
        } finally {
            if (process === proc) {
                process = null
            }
        }
    }

    private fun makeExecutable(file: File) {
        // rwx for owner (needed to exec from app-private storage).
        Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR)
    }

    companion object {
        private const val TAG = "MotisBinaryManager"
        const val BINARY_NAME = "motis-server"

        /** Localhost port the MOTIS server binds to (matches config.yml). */
        const val SERVER_PORT = 8080
        const val BASE_URL = "http://127.0.0.1:$SERVER_PORT"
    }
}

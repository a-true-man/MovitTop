package iam699030.gmail.movitop

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Runs the statically-linked (musl) Linux MOTIS binary as a child process.
 * Android's kernel can execute Linux ELF binaries without JNI, but since
 * Android 10 (API 29) it refuses to exec() a file the app itself wrote to
 * its own data directory (W^X on app-writable storage) — copying the binary
 * out of assets/ and chmod'ing it, as older builds of this class did, fails
 * on real devices. Instead the binary is packaged as a native library
 * (app/src/main/jniLibs/<abi>/libmotis.so, see build_motis_arm64.sh) so the
 * installer extracts it, read-only and already exec-permitted, into
 * [android.content.pm.ApplicationInfo.nativeLibraryDir] — no copy needed.
 */
class MotisBinaryManager(private val context: Context) {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logThread: Thread? = null

    // startProcess() runs on a background thread and only learns the final
    // Process handle once ProcessBuilder.start() returns; if stopProcess()
    // (called from the service's main-thread onDestroy()) runs in that same
    // window, it would see process == null, do nothing, and leave a live,
    // unkillable orphan once startProcess() goes on to assign it. This lock
    // plus re-checking stopRequested right before publishing `process`
    // closes that window either way round.
    private val lifecycleLock = Any()

    @Volatile
    private var stopRequested = false

    /** Absolute path to the installer-extracted, already-executable binary. */
    val binaryPath: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * Verifies the native-library binary is present.
     * @return the path to the ready-to-run binary.
     * @throws IllegalStateException if this ABI's libmotis.so wasn't packaged.
     */
    fun ensureBinary(): File {
        val out = binaryPath
        check(out.exists() && out.length() > 0L) {
            "libmotis.so missing at ${out.absolutePath} — is this device's ABI " +
                "(${android.os.Build.SUPPORTED_ABIS.joinToString()}) built by build_motis_arm64.sh?"
        }
        return out
    }

    /**
     * Starts `libmotis.so server -d data` in [dataRoot], where [dataRoot]
     * contains the compiled `data/` graph (with its own `config.yml`).
     *
     * @return the running [Process], or null if already running / launch failed.
     */
    fun startProcess(dataRoot: File): Process? {
        if (process?.isAliveCompat() == true) {
            Log.i(TAG, "MOTIS process already running")
            return process
        }
        if (stopRequested) return null

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
                // Deliberately NOT redirectOutput()'d to a file: that makes
                // Process.getInputStream() return a dummy/already-closed
                // stream (see ProcessBuilder docs), which silently broke
                // streamToLogcat() below — it would hit EOF immediately and
                // never see a single line MOTIS actually printed. Leaving
                // this as the default Redirect.PIPE is what makes
                // streamToLogcat's reader receive real output, so MOTIS's
                // own stdout/stderr (including the seccomp shim's own
                // diagnostics) show up under this class's logcat tag instead
                // of being invisible — critical for diagnosing a release
                // (non-debuggable) build without adb run-as/root.
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                // MOTIS expands a `~`-relative default path (e.g. a shapes
                // cache dir) internally; without HOME set the process has no
                // home directory to resolve it against and refuses to start.
                environment()["HOME"] = context.filesDir.absolutePath
            }

            val proc = pb.start()
            synchronized(lifecycleLock) {
                if (stopRequested) {
                    // stopProcess() ran while this was launching and found
                    // nothing to stop yet — kill what we just started instead
                    // of publishing a handle nothing will ever stop again.
                    proc.destroy()
                    return null
                }
                process = proc
            }
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

    /** Sends SIGTERM to the child process and clears the handle. Once called, this manager will never start another process (see [startProcess]). */
    fun stopProcess() {
        val proc = synchronized(lifecycleLock) {
            stopRequested = true
            process ?: return
        }
        try {
            if (proc.isAliveCompat()) {
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

    fun isRunning(): Boolean = process?.isAliveCompat() == true

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
            if (proc.isAliveCompat()) {
                Log.w(TAG, "Log reader interrupted", e)
            }
        } finally {
            if (process === proc) {
                process = null
            }
        }
    }

    companion object {
        private const val TAG = "MotisBinaryManager"
        const val BINARY_NAME = "libmotis.so"

        /**
         * [Process.isAlive] only exists from API 26 — minSdk here is 24, and
         * this app crashed with NoSuchMethodError on a real API 24 run before
         * this fix. [Process.exitValue] has existed since API 1; it throws
         * while the process is still running, which is the standard
         * pre-API-26 liveness check.
         */
        private fun Process.isAliveCompat(): Boolean = try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

        /** Localhost port the MOTIS server binds to (matches config.yml). */
        const val SERVER_PORT = 8080
        const val BASE_URL = "http://127.0.0.1:$SERVER_PORT"
    }
}

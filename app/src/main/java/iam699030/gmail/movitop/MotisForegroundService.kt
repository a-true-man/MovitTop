package iam699030.gmail.movitop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Foreground service that hosts the offline MOTIS routing engine for the whole
 * app lifetime.
 *
 * MOTIS runs as a standalone Linux ARM64 child process with a blocking HTTP
 * server loop. A persistent notification keeps the process alive on API 26+.
 */
class MotisForegroundService : Service() {

    private lateinit var binaryManager: MotisBinaryManager

    @Volatile
    private var engineThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        binaryManager = MotisBinaryManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(R.string.motis_engine_starting))
        startEngineIfNeeded()
        return START_STICKY
    }

    private fun startEngineIfNeeded() {
        if (engineThread != null) return

        val dataRoot = motisDataDir(this)
        engineThread = thread(name = "motis-engine", isDaemon = true) {
            try {
                val proc = binaryManager.startProcess(dataRoot)
                val statusRes = if (proc != null && binaryManager.isRunning()) {
                    R.string.motis_engine_ready
                } else {
                    Log.w(TAG, "On-device MOTIS binary unavailable — app will use remote server if configured")
                    R.string.motis_engine_stopped
                }
                updateNotification(statusRes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start on-device MOTIS engine; continuing without crash", e)
                updateNotification(R.string.motis_engine_stopped)
            }
        }
    }

    override fun onDestroy() {
        binaryManager.stopProcess()
        engineThread = null
        super.onDestroy()
    }

    private fun buildNotification(textRes: Int): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(textRes: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(textRes))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.motis_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.motis_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MotisForegroundService"
        private const val CHANNEL_ID = "motis_engine"
        private const val NOTIFICATION_ID = 1001

        /**
         * Root directory pushed by [push_data_to_device.sh]:
         *   motis_data/data/   — compiled MOTIS graph + config.yml
         *   motis_data/israel.map
         */
        fun motisDataDir(context: Context): File =
            File(context.getExternalFilesDir(null), "motis_data")

        fun start(context: Context) {
            val intent = Intent(context, MotisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotisForegroundService::class.java))
        }
    }
}

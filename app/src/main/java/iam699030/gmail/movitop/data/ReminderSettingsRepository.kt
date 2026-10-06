package iam699030.gmail.movitop.data

import android.content.Context

enum class AlertMode { SOUND, VIBRATE, BOTH }

/** The "remind me to get off the bus" settings shown on the live-navigation screen. */
data class ReminderSettings(
    val enabled: Boolean = false,
    val stopsBefore: Int = 2,
    val alertMode: AlertMode = AlertMode.BOTH,
    val volume: Int = 70
)

class ReminderSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): ReminderSettings = ReminderSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        stopsBefore = prefs.getInt(KEY_STOPS_BEFORE, 2),
        alertMode = AlertMode.entries.getOrElse(prefs.getInt(KEY_ALERT_MODE, AlertMode.BOTH.ordinal)) { AlertMode.BOTH },
        volume = prefs.getInt(KEY_VOLUME, 70)
    )

    fun save(settings: ReminderSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_STOPS_BEFORE, settings.stopsBefore)
            .putInt(KEY_ALERT_MODE, settings.alertMode.ordinal)
            .putInt(KEY_VOLUME, settings.volume)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "movitop_reminder_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STOPS_BEFORE = "stops_before"
        private const val KEY_ALERT_MODE = "alert_mode"
        private const val KEY_VOLUME = "volume"
    }
}

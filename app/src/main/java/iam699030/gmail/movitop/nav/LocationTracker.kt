package iam699030.gmail.movitop.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import iam699030.gmail.movitop.data.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Live position updates from the device's satellite GPS **only**
 * ([LocationManager.GPS_PROVIDER]) — deliberately never the network/fused
 * provider, which on stock Android resolves position via Google's online
 * location service and would break full-offline operation. If GPS is off,
 * unsupported, or permission is denied, this simply emits nothing;
 * [NavigationEngine] falls back to schedule-based advancement.
 */
class LocationTracker(private val context: Context) {

    fun isGpsAvailable(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION before collecting
    fun updates(): Flow<GeoPoint> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            close()
            return@callbackFlow
        }

        // A fresh-enough cached fix (e.g. GPS already found the device a
        // couple of minutes ago, in this app session or another) lets a
        // one-shot caller like "center on my location" show a position
        // right away instead of always waiting through a full cold-start
        // fix — which routinely takes well past the 10-20s these callers
        // are willing to wait, especially indoors/under weak sky view.
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
            val ageMs = SystemClock.elapsedRealtime() - last.elapsedRealtimeNanos / 1_000_000L
            if (ageMs in 0..MAX_CACHED_FIX_AGE_MS) {
                trySend(GeoPoint(last.latitude, last.longitude))
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(GeoPoint(location.latitude, location.longitude))
            }
            override fun onProviderDisabled(provider: String) { close() }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_UPDATE_INTERVAL_MS,
            MIN_UPDATE_DISTANCE_M,
            listener
        )

        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_UPDATE_DISTANCE_M = 3f
        private const val MAX_CACHED_FIX_AGE_MS = 2 * 60 * 1_000L

        /**
         * How long a one-shot "my location" lookup (map recenter, search's
         * "use current location") should wait for a GPS fix before giving
         * up. 10s was too tight for a real cold start — GPS satellite-only
         * fixes routinely take 20-30s+, more under a weak sky view — and
         * this app can't fall back to network/fused location (see class
         * doc), so a short timeout here reads as "doesn't work" even when
         * GPS just needed a few more seconds.
         */
        const val ONE_SHOT_FIX_TIMEOUT_MS = 25_000L
    }
}

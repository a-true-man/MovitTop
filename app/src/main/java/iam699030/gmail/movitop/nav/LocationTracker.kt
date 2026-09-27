package iam699030.gmail.movitop.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    }
}

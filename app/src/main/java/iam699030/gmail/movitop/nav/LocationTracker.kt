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
 * Live position updates from the device's location providers.
 *
 * By default ([updates] with `includeNetwork = false`) this is satellite GPS
 * **only** ([LocationManager.GPS_PROVIDER]) — what [NavigationEngine] uses
 * for continuous tracking during live navigation, where it matters that a
 * fix is never wrong by more than a few meters; network-based location's
 * much coarser accuracy (often hundreds of meters, estimated from nearby
 * Wi-Fi/cell towers) could misplace the user on the wrong street or stop
 * entirely.
 *
 * One-shot "locate me" lookups (map recenter, search's "use current
 * location") pass `includeNetwork = true` instead, racing
 * [LocationManager.NETWORK_PROVIDER] alongside GPS and taking whichever
 * reports first. Network location needs an actual internet connection to
 * resolve to coordinates (on stock Android it's resolved by an online
 * Google service from nearby Wi-Fi/cell signatures) — it does nothing on a
 * genuinely offline device — but where a connection exists (e.g. Wi-Fi with
 * no SIM) it's typically far faster than a GPS cold start, especially
 * indoors, where GPS can take 30s+ or never get a fix at all. If no
 * requested provider is enabled, this simply emits nothing; callers fall
 * back accordingly (e.g. [iam699030.gmail.movitop.data.ManualLocationRepository]).
 */
class LocationTracker(private val context: Context) {

    fun isGpsAvailable(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION before collecting
    fun updates(includeNetwork: Boolean = false): Flow<GeoPoint> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (includeNetwork && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        // A fresh-enough cached fix from any requested provider (e.g. GPS
        // already found the device a couple of minutes ago, in this app
        // session or another) lets a one-shot caller like "center on my
        // location" show a position right away instead of always waiting
        // through a full cold-start fix — which routinely takes well past
        // the 10-20s these callers are willing to wait, especially
        // indoors/under weak sky view. elapsedRealtimeNanos is a single
        // monotonic clock shared by every provider, so the freshest cached
        // fix across providers is comparable directly.
        providers.mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { last ->
                val ageMs = SystemClock.elapsedRealtime() - last.elapsedRealtimeNanos / 1_000_000L
                if (ageMs in 0..MAX_CACHED_FIX_AGE_MS) {
                    trySend(GeoPoint(last.latitude, last.longitude))
                }
            }

        // Only close once every requested provider that was live has gone
        // away — e.g. Wi-Fi dropping mid-lookup shouldn't cut off a GPS fix
        // that's still in progress alongside it.
        val remainingProviders = providers.toMutableSet()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(GeoPoint(location.latitude, location.longitude))
            }
            override fun onProviderDisabled(provider: String) {
                remainingProviders.remove(provider)
                if (remainingProviders.isEmpty()) close()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, MIN_UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M, listener)
        }

        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_UPDATE_DISTANCE_M = 3f
        private const val MAX_CACHED_FIX_AGE_MS = 2 * 60 * 1_000L

        /**
         * How long a one-shot "my location" lookup (map recenter, search's
         * "use current location") should wait for a fix before giving up.
         * 10s was too tight for a real cold start — GPS satellite-only
         * fixes routinely take 20-30s+, more under a weak sky view — and
         * this app can't always fall back to network location (see class
         * doc — it needs connectivity the device may not have), so a short
         * timeout here reads as "doesn't work" even when GPS just needed a
         * few more seconds.
         */
        const val ONE_SHOT_FIX_TIMEOUT_MS = 25_000L
    }
}

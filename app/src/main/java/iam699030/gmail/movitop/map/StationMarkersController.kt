package iam699030.gmail.movitop.map

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.data.MotisRepository
import iam699030.gmail.movitop.data.Poi
import iam699030.gmail.movitop.data.PoiCategory
import iam699030.gmail.movitop.data.PoiRepository
import iam699030.gmail.movitop.data.RavKavStation
import iam699030.gmail.movitop.data.RavKavStationsRepository
import iam699030.gmail.movitop.data.TransitStopPlace
import iam699030.gmail.movitop.util.isOpenNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mapsforge.core.graphics.Bitmap as MfBitmap
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.model.common.Observer

/**
 * Adds tappable map markers on top of the offline base map: live transit
 * stops (bus/rail/etc., fetched from the on-device MOTIS server for the
 * current viewport), bundled Rav-Kav card charging/reload points (from
 * gtfs.mot.gov.il's ChargingRavKav dataset, see [RavKavStationsRepository]),
 * and bundled generic POIs (restaurants, shops, pharmacies, ... extracted
 * from OpenStreetMap, see [PoiRepository]).
 *
 * All three layers only appear once the user has zoomed in past their own
 * threshold, and are recomputed (debounced) for the current viewport on every
 * pan/zoom — showing every stop/station/POI at a country-wide zoom would both
 * flood the screen and be wasteful to fetch/render.
 *
 * A transit-stop tap doesn't show its own popup — it hands the stop off to
 * [onStopSelected] so the host activity can drive its results bottom sheet
 * (same surface a route search uses) instead of a second, inconsistent UI.
 * Rav-Kav and POI marker taps open their own info dialog (POI's offers a
 * "set as destination" action that does feed into that same search flow).
 */
class StationMarkersController(
    private val context: Context,
    private val mapView: MapView,
    private val scope: CoroutineScope,
    private val motisRepository: MotisRepository,
    private val ravKavRepository: RavKavStationsRepository,
    private val poiRepository: PoiRepository,
    private val onStopSelected: (TransitStopPlace) -> Unit,
    private val onPoiDestinationPicked: (Poi) -> Unit
) {
    private val stopBitmap: MfBitmap by lazy { loadMarkerBitmap(R.drawable.ic_map_stop) }
    private val ravKavBitmap: MfBitmap by lazy { loadMarkerBitmap(R.drawable.ic_map_ravkav) }
    private val poiBitmaps: Map<PoiCategory, MfBitmap> by lazy {
        PoiCategory.entries.associateWith { category ->
            loadMarkerBitmap(R.drawable.ic_map_poi, poiTintColor(category))
        }
    }

    private val stopMarkers = mutableListOf<Layer>()
    private val ravKavMarkers = mutableListOf<Layer>()
    private val poiMarkers = mutableListOf<Layer>()

    private var debounceJob: Job? = null
    private var stopsFetchJob: Job? = null
    private var ravKavFetchJob: Job? = null
    private var poiFetchJob: Job? = null

    private val positionObserver = Observer { scheduleRefresh() }

    fun start() {
        mapView.model.mapViewPosition.addObserver(positionObserver)
        scheduleRefresh()
    }

    fun destroy() {
        mapView.model.mapViewPosition.removeObserver(positionObserver)
        debounceJob?.cancel()
        stopsFetchJob?.cancel()
        ravKavFetchJob?.cancel()
        poiFetchJob?.cancel()
        clearLayers(stopMarkers)
        clearLayers(ravKavMarkers)
        clearLayers(poiMarkers)
    }

    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            refresh()
        }
    }

    private fun refresh() {
        val zoom = mapView.model.mapViewPosition.zoomLevel.toInt()
        val box = mapView.boundingBox ?: return
        val southWest = GeoPoint(box.minLatitude, box.minLongitude)
        val northEast = GeoPoint(box.maxLatitude, box.maxLongitude)

        if (zoom < RAVKAV_MIN_ZOOM) {
            clearLayers(ravKavMarkers)
        } else {
            ravKavFetchJob?.cancel()
            ravKavFetchJob = scope.launch {
                val stations = ravKavRepository.stationsInBounds(southWest, northEast)
                    .take(MAX_RAVKAV_MARKERS)
                showRavKavMarkers(stations)
            }
        }

        if (zoom < STOPS_MIN_ZOOM) {
            clearLayers(stopMarkers)
        } else {
            stopsFetchJob?.cancel()
            stopsFetchJob = scope.launch {
                val stops = motisRepository.stopsInBounds(southWest, northEast)
                    .take(MAX_STOP_MARKERS)
                showStopMarkers(stops)
            }
        }

        if (zoom < POI_MIN_ZOOM) {
            clearLayers(poiMarkers)
        } else {
            poiFetchJob?.cancel()
            poiFetchJob = scope.launch {
                val pois = poiRepository.poisInBounds(southWest, northEast)
                    .take(MAX_POI_MARKERS)
                showPoiMarkers(pois)
            }
        }
    }

    private fun showStopMarkers(stops: List<TransitStopPlace>) {
        clearLayers(stopMarkers)
        stops.forEach { stop ->
            val marker = object : Marker(
                LatLong(stop.point.lat, stop.point.lon),
                stopBitmap,
                0,
                -stopBitmap.height / 2
            ) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                    if (!contains(layerXY, tapXY)) return false
                    onStopSelected(stop)
                    return true
                }
            }
            mapView.layerManager.layers.add(marker)
            stopMarkers += marker
        }
    }

    private fun showRavKavMarkers(stations: List<RavKavStation>) {
        clearLayers(ravKavMarkers)
        stations.forEach { station ->
            val marker = object : Marker(
                LatLong(station.point.lat, station.point.lon),
                ravKavBitmap,
                0,
                -ravKavBitmap.height / 2
            ) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                    if (!contains(layerXY, tapXY)) return false
                    showRavKavDialog(station)
                    return true
                }
            }
            mapView.layerManager.layers.add(marker)
            ravKavMarkers += marker
        }
    }

    private fun showPoiMarkers(pois: List<Poi>) {
        clearLayers(poiMarkers)
        pois.forEach { poi ->
            val bitmap = poiBitmaps.getValue(poi.category)
            val marker = object : Marker(
                LatLong(poi.point.lat, poi.point.lon),
                bitmap,
                0,
                -bitmap.height / 2
            ) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                    if (!contains(layerXY, tapXY)) return false
                    showPoiDialog(poi)
                    return true
                }
            }
            mapView.layerManager.layers.add(marker)
            poiMarkers += marker
        }
    }

    private fun showPoiDialog(poi: Poi) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_poi, null)
        view.findViewById<android.widget.TextView>(R.id.poiName).text = poi.name

        val openBadge = view.findViewById<android.widget.TextView>(R.id.poiOpenNow)
        when (isOpenNow(poi.hoursByDay)) {
            true -> {
                openBadge.visibility = android.view.View.VISIBLE
                openBadge.text = context.getString(R.string.poi_open_now)
                openBadge.setTextColor(ContextCompat.getColor(context, R.color.movitop_departs_soon))
            }
            false -> {
                openBadge.visibility = android.view.View.VISIBLE
                openBadge.text = context.getString(R.string.poi_closed_now)
                openBadge.setTextColor(ContextCompat.getColor(context, R.color.movitop_text_secondary))
            }
            null -> openBadge.visibility = android.view.View.GONE
        }

        val lines = mutableListOf<String>()
        lines += context.getString(R.string.poi_label_category) + ": " + context.getString(poi.category.labelRes)
        if (poi.address.isNotBlank()) lines += context.getString(R.string.ravkav_label_address) + ": " + poi.address
        val hours = formatKnownWeeklyHours(poi.hoursByDay)
        if (hours.isNotBlank()) lines += context.getString(R.string.ravkav_label_hours) + ": " + hours
        view.findViewById<android.widget.TextView>(R.id.poiBody).text = lines.joinToString("\n")

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setNegativeButton(R.string.dialog_close_button, null)
            .create()
        view.findViewById<android.view.View>(R.id.poiSetDestinationButton).setOnClickListener {
            onPoiDestinationPicked(poi)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun clearLayers(layers: MutableList<Layer>) {
        if (layers.isEmpty()) return
        layers.forEach { mapView.layerManager.layers.remove(it) }
        layers.clear()
    }

    private fun showRavKavDialog(station: RavKavStation) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ravkav_station, null)
        view.findViewById<android.widget.TextView>(R.id.ravkavName).text =
            station.name.ifBlank { context.getString(R.string.ravkav_station_generic_name) }

        val lines = mutableListOf<String>()
        if (station.city.isNotBlank()) lines += context.getString(R.string.ravkav_label_city) + ": " + station.city
        if (station.address.isNotBlank()) lines += context.getString(R.string.ravkav_label_address) + ": " + station.address
        if (station.agency.isNotBlank()) lines += context.getString(R.string.ravkav_label_agency) + ": " + station.agency
        if (station.phone.isNotBlank()) lines += context.getString(R.string.ravkav_label_phone) + ": " + station.phone
        val hours = formatWeeklyHours(station.hoursByDay)
        if (hours.isNotBlank()) lines += context.getString(R.string.ravkav_label_hours) + ": " + hours
        if (station.acceptsCash) lines += context.getString(R.string.ravkav_accepts_cash)
        if (station.acceptsCreditCard) lines += context.getString(R.string.ravkav_accepts_credit_card)
        if (station.accessible) lines += context.getString(R.string.ravkav_accessible)

        view.findViewById<android.widget.TextView>(R.id.ravkavBody).text = lines.joinToString("\n")

        MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(R.string.dialog_close_button, null)
            .show()
    }

    /** Groups Sun..Sat into contiguous same-hours ranges, e.g. "א׳-ה׳: 08:00-17:00; ו׳: 08:00-13:00". */
    private fun formatWeeklyHours(hoursByDay: List<String>): String {
        if (hoursByDay.size != 7) return ""
        val dayLabels = listOf(
            R.string.ravkav_days_sun, R.string.ravkav_days_mon, R.string.ravkav_days_tue,
            R.string.ravkav_days_wed, R.string.ravkav_days_thu, R.string.ravkav_days_fri,
            R.string.ravkav_days_sat
        ).map { context.getString(it) }

        val groups = mutableListOf<String>()
        var i = 0
        while (i < 7) {
            var j = i
            while (j + 1 < 7 && hoursByDay[j + 1] == hoursByDay[i]) j++
            val label = if (i == j) dayLabels[i] else "${dayLabels[i]}-${dayLabels[j]}"
            val text = hoursByDay[i].ifBlank { context.getString(R.string.ravkav_hours_closed) }
            groups += "$label: $text"
            i = j + 1
        }
        return groups.joinToString("; ")
    }

    private fun loadMarkerBitmap(drawableRes: Int, tintColor: Int? = null): MfBitmap {
        val drawable = ContextCompat.getDrawable(context, drawableRes)!!.mutate()
        if (tintColor != null) {
            @Suppress("DEPRECATION")
            drawable.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }
        return AndroidGraphicFactory.convertToBitmap(drawable)
    }

    private fun poiTintColor(category: PoiCategory): Int = ContextCompat.getColor(
        context,
        when (category) {
            PoiCategory.FOOD_DRINK -> R.color.movitop_poi_food_drink
            PoiCategory.SHOPPING -> R.color.movitop_poi_shopping
            PoiCategory.HEALTH -> R.color.movitop_poi_health
            PoiCategory.FINANCE -> R.color.movitop_poi_finance
            PoiCategory.LEISURE -> R.color.movitop_poi_leisure
            PoiCategory.OTHER -> R.color.movitop_poi_other
        }
    )

    /**
     * Same grouping as [formatWeeklyHours] but silently skips days with no
     * parsed hours instead of labeling them "closed" — a POI's blank day
     * means extract_osm_pois.py couldn't confidently parse that day's OSM
     * `opening_hours` clause, not that it's confirmed closed (see
     * [iam699030.gmail.movitop.util.isOpenNow]'s same null-means-unknown rule).
     */
    private fun formatKnownWeeklyHours(hoursByDay: List<String>): String {
        if (hoursByDay.size != 7) return ""
        val dayLabels = listOf(
            R.string.ravkav_days_sun, R.string.ravkav_days_mon, R.string.ravkav_days_tue,
            R.string.ravkav_days_wed, R.string.ravkav_days_thu, R.string.ravkav_days_fri,
            R.string.ravkav_days_sat
        ).map { context.getString(it) }

        val groups = mutableListOf<String>()
        var i = 0
        while (i < 7) {
            var j = i
            while (j + 1 < 7 && hoursByDay[j + 1] == hoursByDay[i]) j++
            if (hoursByDay[i].isNotBlank()) {
                val label = if (i == j) dayLabels[i] else "${dayLabels[i]}-${dayLabels[j]}"
                groups += "$label: ${hoursByDay[i]}"
            }
            i = j + 1
        }
        return groups.joinToString("; ")
    }

    companion object {
        private const val RAVKAV_MIN_ZOOM = 14
        private const val STOPS_MIN_ZOOM = 15
        private const val POI_MIN_ZOOM = 16
        private const val MAX_RAVKAV_MARKERS = 300
        private const val MAX_STOP_MARKERS = 300
        private const val MAX_POI_MARKERS = 300
        private const val DEBOUNCE_MILLIS = 400L
    }
}

package iam699030.gmail.movitop.map

import android.content.Context
import androidx.core.content.ContextCompat
import iam699030.gmail.movitop.R
import iam699030.gmail.movitop.data.GeoPoint
import iam699030.gmail.movitop.nav.NavigationStep
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.graphics.Bitmap as MfBitmap
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline

/**
 * Draws the live-navigation screen's own map: the current step's leg path
 * (dashed, same [Polyline] approach as [iam699030.gmail.movitop.MainActivity]'s
 * route-overview drawing) plus a position marker — one step at a time, unlike
 * the overview screen which draws the whole trip at once. Built on the same
 * plain-class-owning-its-own-layers template as `StationMarkersController`.
 */
class LiveNavMapController(
    private val context: Context,
    private val mapView: MapView
) {
    private var routeOverlay: Polyline? = null
    private var positionMarker: Marker? = null
    private val positionBitmap: MfBitmap by lazy {
        AndroidGraphicFactory.convertToBitmap(
            ContextCompat.getDrawable(context, R.drawable.ic_nav_position_dot)!!.mutate()
        )
    }

    /** Call once per step change (manual or automatic advance). */
    fun showStep(step: NavigationStep) {
        clearRoute()
        val legPoints = when (step) {
            is NavigationStep.Walk -> step.legPoints
            is NavigationStep.Ride -> step.legPoints
            else -> emptyList()
        }
        if (legPoints.size >= 2) {
            val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = ContextCompat.getColor(context, R.color.movitop_primary)
                strokeWidth = 14f
                setStyle(Style.STROKE)
                setDashPathEffect(floatArrayOf(20f, 14f))
            }
            val line = Polyline(paint, AndroidGraphicFactory.INSTANCE)
            legPoints.forEach { line.latLongs.add(LatLong(it.lat, it.lon)) }
            mapView.layerManager.layers.add(line)
            routeOverlay = line
        }

        val focus = legPoints.firstOrNull() ?: step.point
        placeOrMoveMarker(focus)
        mapView.model.mapViewPosition.center = LatLong(focus.lat, focus.lon)
        mapView.model.mapViewPosition.zoomLevel = 17.toByte()
    }

    /** Call on every GPS fix while a step is active, to move the live position dot. */
    fun updatePosition(point: GeoPoint) {
        placeOrMoveMarker(point)
        mapView.model.mapViewPosition.center = LatLong(point.lat, point.lon)
    }

    private fun placeOrMoveMarker(point: GeoPoint) {
        val marker = positionMarker
        if (marker == null) {
            val created = Marker(LatLong(point.lat, point.lon), positionBitmap, 0, -positionBitmap.height / 2)
            mapView.layerManager.layers.add(created)
            positionMarker = created
        } else {
            marker.setLatLong(LatLong(point.lat, point.lon))
            marker.requestRedraw()
        }
    }

    fun destroy() {
        clearRoute()
        positionMarker?.let { mapView.layerManager.layers.remove(it) }
        positionMarker = null
    }

    private fun clearRoute() {
        routeOverlay?.let { mapView.layerManager.layers.remove(it) }
        routeOverlay = null
    }
}

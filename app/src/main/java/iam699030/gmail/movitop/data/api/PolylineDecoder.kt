package iam699030.gmail.movitop.data.api

import iam699030.gmail.movitop.data.GeoPoint
import kotlin.math.pow

/**
 * Decodes Google encoded polylines. MOTIS `/api/v1` uses precision 7
 * (factor 1e7); the precision is also reported per leg in `legGeometry.precision`.
 */
object PolylineDecoder {

    fun decode(encoded: String?, precision: Int): List<GeoPoint> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val factor = 10.0.pow(precision)
        val result = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            lat += decodeDelta(encoded, index).also { index = it.nextIndex }.value
            lng += decodeDelta(encoded, index).also { index = it.nextIndex }.value
            result.add(GeoPoint(lat / factor, lng / factor))
        }
        return result
    }

    private data class Delta(val value: Int, val nextIndex: Int)

    private fun decodeDelta(encoded: String, start: Int): Delta {
        var index = start
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return Delta(delta, index)
    }
}

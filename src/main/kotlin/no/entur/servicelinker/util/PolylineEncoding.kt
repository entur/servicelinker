package no.entur.servicelinker.util

import no.entur.servicelinker.model.Coordinate
import java.lang.Character.toChars

/**
 * See https://developers.google.com/maps/documentation/utilities/polylinealgorithm for details
 */
object PolylineEncoding {
    fun decode(encodedPath: String): List<Coordinate> {
        val len = encodedPath.length

        val path = mutableListOf<Coordinate>()
        var index = 0
        var lat = 0L
        var lng = 0L
        val doubleShift = 1e-5

        while (index < len) {
            var result = 1
            var shift = 0
            var b: Int
            do {
                b = encodedPath[index++].code - 64
                result += b shl shift
                shift += 5
            } while (b >= 0x1f)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            result = 1
            shift = 0
            do {
                b = encodedPath[index++].code - 64
                result += b shl shift
                shift += 5
            } while (b >= 0x1f)
            lng += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            path.add(Coordinate(lat * doubleShift, lng * doubleShift))
        }
        return path
    }

    fun encode(path: List<Coordinate>): String {
        var lastLat = 0L
        var lastLng = 0L

        val result = StringBuilder()
        val longShift = 1e5.toBigDecimal()
        for (point in path) {
            val lat = (point.latitude * longShift).toLong()
            val lng = (point.longitude * longShift).toLong()

            val dLat = lat - lastLat
            val dLng = lng - lastLng

            encode(dLat, result)
            encode(dLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encode(
        v: Long,
        result: StringBuilder,
    ) {
        var v = v
        v = if (v < 0) (v shl 1).inv() else v shl 1
        while (v >= 0x20) {
            result.append(toChars(((0x20L or (v and 0x1fL)) + 63).toInt()))
            v = v shr 5
        }
        result.append(toChars((v + 63).toInt()))
    }
}

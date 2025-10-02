package no.entur.servicelinker.util

/**
 * Utility class for decoding Google Polyline encoded strings
 * Based on the algorithm described at https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object PolylineDecoder {

    /**
     * Decodes a polyline encoded string into a list of coordinate pairs [longitude, latitude]
     */
    fun decode(encoded: String): List<List<Double>> {
        val coordinates = mutableListOf<List<Double>>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0

            // Decode latitude
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val deltaLat = if ((result and 1) != 0) -(result shr 1) else (result shr 1)
            lat += deltaLat

            shift = 0
            result = 0

            // Decode longitude
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val deltaLng = if ((result and 1) != 0) -(result shr 1) else (result shr 1)
            lng += deltaLng

            coordinates.add(listOf(lng / 1e5, lat / 1e5))
        }

        return coordinates
    }
}

package no.entur.servicelinker.util

import com.fasterxml.jackson.databind.ObjectMapper
import no.entur.servicelinker.model.Coordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import kotlin.test.Test

class PolylineEncodingTest {
    private val MAX_DIFF = 0.00001.toBigDecimal()

    @Test
    fun `decoded coordinates carry no floating point noise`() {
        // Straight from the polyline5 grid: five decimals, exactly. Multiplying by 1e-5 in double
        // yields 59.91395000000001, which BigDecimal preserves into the NeTEx posList.
        val objectMapper = ObjectMapper()
        val polylineJson = objectMapper.readTree(File("src/test/resources/sample-osrm-polyline.json"))
        val decoded = PolylineEncoding.decode(polylineJson["routes"][0]["geometry"].asText())

        decoded.forEach { coordinate ->
            assertEquals(5, coordinate.latitude.scale(), "Unexpected scale in ${coordinate.latitude}")
            assertEquals(5, coordinate.longitude.scale(), "Unexpected scale in ${coordinate.longitude}")
        }
    }

    @Test
    fun testEncodeDecode() {
        val objectMapper = ObjectMapper()

        val polylineFile = File("src/test/resources/sample-osrm-polyline.json")
        val polylineJson = objectMapper.readTree(polylineFile)
        val encodedPolyline = polylineJson["routes"][0]["geometry"].asText()

        val geojsonFile = File("src/test/resources/sample-osrm-geojson.json")
        val geojsonJson = objectMapper.readTree(geojsonFile)
        val geojsonCoordinates = geojsonJson["routes"][0]["geometry"]["coordinates"]

        val expectedCoordinates = mutableListOf<Coordinate>()
        for (coordArray in geojsonCoordinates) {
            val longitude = coordArray[0].asDouble()
            val latitude = coordArray[1].asDouble()
            expectedCoordinates.add(Coordinate(latitude, longitude))
        }

        val decodedCoordinates = PolylineEncoding.decode(encodedPolyline)

        assertEquals(decodedCoordinates.size, 845)
        assertEquals(
            expectedCoordinates.size,
            decodedCoordinates.size,
            "Decoded coordinates count should match expected coordinates count",
        )

        for (i in expectedCoordinates.indices) {
            val expected = expectedCoordinates[i]
            val actual = decodedCoordinates[i]

            val latDiff = expected.latitude - actual.latitude
            val lonDiff = expected.longitude - actual.longitude

            assertTrue(
                latDiff < MAX_DIFF,
                "Latitude difference too large at index $i: expected ${expected.latitude}, actual ${actual.latitude}",
            )
            assertTrue(
                lonDiff < MAX_DIFF,
                "Longitude difference too large at index $i: expected ${expected.longitude}, actual ${actual.longitude}",
            )
        }
        val reEncodedPolyline = PolylineEncoding.encode(decodedCoordinates)
        val reDecodedCoordinates = PolylineEncoding.decode(reEncodedPolyline)

        assertEquals(decodedCoordinates.size, reDecodedCoordinates.size, "Round-trip should preserve coordinate count")

        for (i in decodedCoordinates.indices) {
            val original = decodedCoordinates[i]
            val roundTrip = reDecodedCoordinates[i]

            val latDiff = original.latitude - roundTrip.latitude
            val lonDiff = original.longitude - roundTrip.longitude

            assertTrue(latDiff < MAX_DIFF, "Round-trip latitude difference too large at index $i")
            assertTrue(lonDiff < MAX_DIFF, "Round-trip longitude difference too large at index $i")
        }
    }
}

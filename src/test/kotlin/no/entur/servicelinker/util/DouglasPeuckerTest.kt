package no.entur.servicelinker.util

import com.fasterxml.jackson.databind.ObjectMapper
import no.entur.servicelinker.model.Coordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

class DouglasPeuckerTest {
    @Test
    fun `keeps lines of fewer than three points untouched`() {
        val points = listOf(Coordinate(59.911, 10.75), Coordinate(59.912, 10.75))
        assertEquals(points, DouglasPeucker.simplify(points, 1.0))
        assertEquals(points.take(1), DouglasPeucker.simplify(points.take(1), 1.0))
        assertEquals(emptyList<Coordinate>(), DouglasPeucker.simplify(emptyList(), 1.0))
    }

    @Test
    fun `drops points on a straight line`() {
        val points =
            listOf(
                Coordinate(59.9100, 10.7500),
                Coordinate(59.9110, 10.7500),
                Coordinate(59.9120, 10.7500),
                Coordinate(59.9130, 10.7500),
            )
        assertEquals(listOf(points.first(), points.last()), DouglasPeucker.simplify(points, 1.0))
    }

    @Test
    fun `keeps points that deviate more than the tolerance`() {
        // ~0.00005 degrees latitude is ~5.5 m off the straight line between the endpoints
        val points =
            listOf(
                Coordinate(59.9100, 10.7500),
                Coordinate(59.9110, 10.7505),
                Coordinate(59.91105, 10.7500),
                Coordinate(59.9120, 10.7500),
            )
        val simplified = DouglasPeucker.simplify(points, 1.0)
        assertTrue(simplified.contains(points[1]), "Deviating point should be kept")
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun `keeps endpoints of a closed loop`() {
        val start = Coordinate(59.9100, 10.7500)
        val points = listOf(start, Coordinate(59.9110, 10.7520), start)
        assertEquals(points, DouglasPeucker.simplify(points, 1.0))
    }

    @Test
    fun `collapses a run of duplicate points`() {
        val start = Coordinate(59.9100, 10.7500)
        val end = Coordinate(59.9110, 10.7500)
        val points = listOf(start, start, start, end)
        assertEquals(listOf(start, end), DouglasPeucker.simplify(points, 1.0))
    }

    @Test
    fun `returns the line unchanged for a tolerance that is not positive`() {
        val points =
            listOf(
                Coordinate(59.9100, 10.7500),
                Coordinate(59.9110, 10.7500),
                Coordinate(59.9120, 10.7500),
            )
        assertEquals(points, DouglasPeucker.simplify(points, 0.0))
        assertEquals(points, DouglasPeucker.simplify(points, -1.0))
        // NaN would pass every distance comparison as false and drop every interior point
        assertEquals(points, DouglasPeucker.simplify(points, Double.NaN))
    }

    @Test
    fun `simplified route stays within tolerance of the original`() {
        val original = sampleRoute()

        val simplified = DouglasPeucker.simplify(original, 1.0)

        assertTrue(simplified.size < original.size, "Simplification should remove points")
        assertEquals(original.first(), simplified.first())
        assertEquals(original.last(), simplified.last())
        original.forEach { point ->
            val distance = simplified.zipWithNext().minOf { (a, b) -> crossTrackDistance(point, a, b) }
            assertTrue(distance <= 1.0, "Original point $point drifted $distance m from the simplified line")
        }
    }

    @Test
    fun `reduces the sample route by roughly two thirds at one meter`() {
        val original = sampleRoute()
        assertEquals(845, original.size)
        // Golden value: pins the scale of the distance metric, which the tolerance check alone
        // cannot (a metric that is uniformly too small still satisfies its own bound).
        assertEquals(313, DouglasPeucker.simplify(original, 1.0).size)
    }

    private fun sampleRoute(): List<Coordinate> {
        val json = ObjectMapper().readTree(File("src/test/resources/sample-osrm-polyline.json"))
        return PolylineEncoding.decode(json["routes"][0]["geometry"].asText())
    }

    /**
     * Spherical cross-track distance in meters, clamped to the segment. Deliberately different
     * math from the equirectangular projection under test, so an error in that projection shows
     * up here instead of cancelling out.
     */
    private fun crossTrackDistance(
        point: Coordinate,
        start: Coordinate,
        end: Coordinate,
    ): Double {
        val segmentLength = start.haversineDistanceTo(end)
        if (segmentLength == 0.0) return start.haversineDistanceTo(point)

        val angularDistance = start.haversineDistanceTo(point) / EARTH_RADIUS_METERS
        val crossTrack = asin(sin(angularDistance) * sin(bearing(start, point) - bearing(start, end)))
        val alongTrack = atan2(sin(angularDistance) * cos(crossTrack), cos(angularDistance)) * EARTH_RADIUS_METERS
        return when {
            alongTrack < 0 -> start.haversineDistanceTo(point)
            alongTrack > segmentLength -> end.haversineDistanceTo(point)
            else -> abs(crossTrack) * EARTH_RADIUS_METERS
        }
    }

    private fun bearing(
        from: Coordinate,
        to: Coordinate,
    ): Double {
        val lat1 = Math.toRadians(from.latitude.toDouble())
        val lat2 = Math.toRadians(to.latitude.toDouble())
        val dLon = Math.toRadians(to.longitude.toDouble() - from.longitude.toDouble())
        return atan2(sin(dLon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

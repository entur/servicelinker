package no.entur.servicelinker.util

import no.entur.servicelinker.model.Coordinate
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Ramer-Douglas-Peucker line simplification.
 * See https://en.wikipedia.org/wiki/Ramer-Douglas-Peucker_algorithm
 *
 * Distances are measured in meters using an equirectangular projection local to each
 * segment, which is accurate well below the tolerances we care about at Nordic latitudes.
 */
object DouglasPeucker {
    private const val METERS_PER_DEGREE_LAT = 110_574.0
    private const val METERS_PER_DEGREE_LON_AT_EQUATOR = 111_320.0

    /**
     * Drop points that lie within [toleranceMeters] of the retained line. Endpoints are always kept.
     * Lines of fewer than three points, and tolerances that are not positive, are returned unchanged.
     */
    fun simplify(
        points: List<Coordinate>,
        toleranceMeters: Double,
    ): List<Coordinate> {
        // Negated so a NaN tolerance is rejected too - it would otherwise pass every
        // comparison below as false and silently drop every interior point.
        if (points.size < 3 || !(toleranceMeters > 0)) return points.toList()

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true

        // Iterative rather than recursive: a pathological geometry could otherwise recurse
        // once per point.
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.addLast(0 to points.lastIndex)
        while (pending.isNotEmpty()) {
            val (first, last) = pending.removeLast()
            if (last - first < 2) continue

            var farthest = -1
            // Seeded at the tolerance so a point closer than that can never win: the sub-range
            // is then within tolerance of its own segment and collapses to its endpoints.
            var maxDistance = toleranceMeters
            for (i in first + 1 until last) {
                val distance = distanceToSegment(points[i], points[first], points[last])
                if (distance > maxDistance) {
                    maxDistance = distance
                    farthest = i
                }
            }
            if (farthest < 0) continue

            keep[farthest] = true
            pending.addLast(first to farthest)
            pending.addLast(farthest to last)
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun distanceToSegment(
        point: Coordinate,
        start: Coordinate,
        end: Coordinate,
    ): Double {
        val lonScale = METERS_PER_DEGREE_LON_AT_EQUATOR * cos(Math.toRadians(start.latitude.toDouble()))
        val px = (point.longitude.toDouble() - start.longitude.toDouble()) * lonScale
        val py = (point.latitude.toDouble() - start.latitude.toDouble()) * METERS_PER_DEGREE_LAT
        val ex = (end.longitude.toDouble() - start.longitude.toDouble()) * lonScale
        val ey = (end.latitude.toDouble() - start.latitude.toDouble()) * METERS_PER_DEGREE_LAT

        val segmentLengthSquared = ex * ex + ey * ey
        if (segmentLengthSquared == 0.0) return sqrt(px * px + py * py)

        // Measure against the segment, not the infinite line through it: the retained geometry
        // is a chain of segments, so this is the bound we actually promise. It also keeps a
        // loop back to its own start from having a meaningless perpendicular.
        val t = ((px * ex + py * ey) / segmentLengthSquared).coerceIn(0.0, 1.0)
        val dx = px - t * ex
        val dy = py - t * ey
        return sqrt(dx * dx + dy * dy)
    }
}

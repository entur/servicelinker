package no.entur.servicelinker.model

import java.math.BigDecimal
import kotlin.math.*

data class Coordinate(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
) {
    override fun toString(): String = "$longitude,$latitude"

    constructor(lat: Double, lon: Double) : this(BigDecimal.valueOf(lat), BigDecimal.valueOf(lon))

    fun haversineDistanceTo(other: Coordinate): Double {
        val lat1 = Math.toRadians(latitude.toDouble())
        val lat2 = Math.toRadians(other.latitude.toDouble())
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude.toDouble() - longitude.toDouble())
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

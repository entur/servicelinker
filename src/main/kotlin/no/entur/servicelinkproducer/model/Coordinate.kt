package no.entur.servicelinkproducer.model

import java.math.BigDecimal

data class Coordinate(
    val latitude: BigDecimal,
    val longitude: BigDecimal
) {
    override fun toString(): String = "$longitude,$latitude"

    constructor(lat: Double, lon: Double) : this(BigDecimal.valueOf(lat), BigDecimal.valueOf(lon))
}

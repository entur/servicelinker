package no.entur.servicelinker.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import no.entur.servicelinker.util.PolylineEncoding

data class OSRMResponse(
    val routes: List<OSRMRoute>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OSRMRoute(
    val distance: Double,
    val duration: Double,
    private val geometry: String,
) {
    /** The decoded geometry, in route order. */
    @get:JsonIgnore
    val path: List<Coordinate> by lazy { PolylineEncoding.decode(geometry) }

    /** Encoded size, used to weigh the route cache without decoding the geometry. */
    @get:JsonIgnore
    val geometryLength: Int
        get() = geometry.length
}

data class DirectProcessingResult(
    val success: Boolean,
    val processedXml: String?,
    val addedServiceLinksCount: Int,
    val errorMessage: String? = null,
)

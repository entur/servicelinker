package no.entur.servicelinker.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.maps.internal.PolylineEncoding

data class OSRMResponse(
    val routes: List<OSRMRoute>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OSRMRoute(
    val distance: Double,
    val duration: Double,
    private val geometry: String
) {
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val coordinates: List<List<Double>>
        get() = PolylineEncoding.decode(geometry).map { listOf(it.lng, it.lat) }
}

data class ProcessingRequest(
    val gcsFilePath: String,
    val outputBucket: String? = null
)

data class ProcessingResult(
    val success: Boolean,
    val outputFilePath: String?,
    val addedServiceLinksCount: Int,
    val errorMessage: String? = null
)

data class DirectProcessingResult(
    val success: Boolean,
    val processedXml: String?,
    val addedServiceLinksCount: Int,
    val errorMessage: String? = null
)

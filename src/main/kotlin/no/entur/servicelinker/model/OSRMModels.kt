package no.entur.servicelinker.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import no.entur.servicelinker.util.PolylineDecoder

data class OSRMRequest(
    val coordinates: List<List<Double>>,
    val overview: String = "full",
    val geometries: String = "geojson"
)

data class OSRMResponse(
    val routes: List<OSRMRoute>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OSRMRoute(
    val distance: Double,
    val duration: Double,
    val geometry: String
) {
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val coordinates: List<List<Double>>
        get() = PolylineDecoder.decode(geometry)
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

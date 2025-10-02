package no.entur.servicelinker.model

data class OSRMRequest(
    val coordinates: List<List<Double>>,
    val overview: String = "full",
    val geometries: String = "geojson"
)

data class OSRMResponse(
    val routes: List<OSRMRoute>
)

data class OSRMRoute(
    val distance: Double,
    val duration: Double,
    val geometry: OSRMGeometry
)

data class OSRMGeometry(
    val coordinates: List<List<Double>>
)

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

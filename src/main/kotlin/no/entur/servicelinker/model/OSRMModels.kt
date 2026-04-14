package no.entur.servicelinker.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import no.entur.servicelinker.util.PolylineEncoding
import java.math.BigDecimal

data class OSRMResponse(
    val routes: List<OSRMRoute>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OSRMRoute(
    val distance: Double,
    val duration: Double,
    private val geometry: String,
) {
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val coordinates: List<List<BigDecimal>>
        get() = PolylineEncoding.decode(geometry).map { listOf(it.longitude, it.latitude) }
}

data class DirectProcessingResult(
    val success: Boolean,
    val processedXml: String?,
    val addedServiceLinksCount: Int,
    val errorMessage: String? = null,
)

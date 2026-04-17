package no.entur.servicelinker.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.Coordinate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.io.StringReader
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

@ConfigurationProperties(prefix = "nsr")
data class NsrProperties(
    val baseUrl: String = "https://api.entur.io/stop-places/v1/read",
    val timeout: Long = 10000,
    val cacheCoordinates: Boolean = true,
    val cacheMaxSize: Int = 50_000,
    val cacheTtlHours: Int = 24,
)

@Service
class NsrService(
    private val nsrProperties: NsrProperties,
) {
    private val logger = LoggerFactory.getLogger(NsrService::class.java)

    // LRU cache: ~300 bytes per entry, 50k entries ≈ 15 MB; entries expire after 24h
    private val coordinateCache: Cache<String, Coordinate> =
        Caffeine
            .newBuilder()
            .maximumSize(nsrProperties.cacheMaxSize.toLong())
            .expireAfterWrite(nsrProperties.cacheTtlHours.toLong(), TimeUnit.HOURS)
            .build()

    private val webClient: WebClient by lazy {
        WebClient
            .builder()
            .baseUrl(nsrProperties.baseUrl)
            .defaultHeader("ET-Client-Name", "servicelinker")
            .defaultHeader("ET-Client-ID", "servicelinker")
            .defaultHeader("Accept", "application/xml")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .followRedirect(true)
                        .responseTimeout(Duration.ofMillis(nsrProperties.timeout)),
                ),
            ).build()
    }

    suspend fun getQuayCoordinates(quayId: String): Coordinate? {
        if (nsrProperties.cacheCoordinates) {
            coordinateCache.getIfPresent(quayId)?.let { cached ->
                logger.debug("Using cached coordinates for {}", quayId)
                return cached
            }
        }

        return try {
            logger.debug("Looking up coordinates for quay {} from NSR", quayId)

            val responseBody =
                webClient
                    .get()
                    .uri("/quays/{quayId}", quayId)
                    .retrieve()
                    .awaitBody<String>()

            val coordinate = parseCoordinateFromXml(responseBody)

            if (coordinate != null && nsrProperties.cacheCoordinates) {
                coordinateCache.put(quayId, coordinate)
                logger.debug("Cached coordinates for {}: {}", quayId, coordinate)
            }

            coordinate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to get coordinates for quay {}: {}", quayId, e.message)
            null
        }
    }

    private fun parseCoordinateFromXml(xml: String): Coordinate? {
        return try {
            val reader = xmlInputFactory.createXMLStreamReader(StringReader(xml))
            var latitude: java.math.BigDecimal? = null
            var longitude: java.math.BigDecimal? = null
            var inCentroid = false
            var inLocation = false

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        when (reader.localName) {
                            "Centroid" -> inCentroid = true
                            "Location" -> if (inCentroid) inLocation = true
                            "Latitude" -> if (inLocation) latitude = reader.elementText.trim().toBigDecimalOrNull()
                            "Longitude" -> if (inLocation) longitude = reader.elementText.trim().toBigDecimalOrNull()
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
                            "Centroid" -> inCentroid = false
                            "Location" -> inLocation = false
                        }
                    }
                }
                if (latitude != null && longitude != null) break
            }
            reader.close()

            if (latitude != null && longitude != null) Coordinate(latitude, longitude) else null
        } catch (e: Exception) {
            logger.warn("Failed to parse NSR XML response: {}", e.message)
            null
        }
    }

    fun getCacheSize(): Long = coordinateCache.estimatedSize()

    private companion object {
        val xmlInputFactory: XMLInputFactory = XMLInputFactory.newInstance()
    }
}

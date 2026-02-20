package no.entur.servicelinker.service

import no.entur.servicelinker.model.Coordinate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.w3c.dom.Element
import reactor.netty.http.client.HttpClient
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.Collections
import javax.xml.parsers.DocumentBuilderFactory

@ConfigurationProperties(prefix = "nsr")
data class NsrProperties(
    val baseUrl: String = "https://api.entur.io/stop-places/v1/read",
    val timeout: Long = 10000,
    val cacheCoordinates: Boolean = true,
    val cacheMaxSize: Int = 50_000
)

@Service
class NsrService(
    private val nsrProperties: NsrProperties
) {
    private val logger = LoggerFactory.getLogger(NsrService::class.java)

    // LRU cache: ~300 bytes per entry, 50k entries ≈ 15 MB
    private val coordinateCache: MutableMap<String, Coordinate> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Coordinate>(nsrProperties.cacheMaxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Coordinate>?): Boolean {
                return size > nsrProperties.cacheMaxSize
            }
        }
    )

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(nsrProperties.baseUrl)
            .defaultHeader("ET-Client-Name", "servicelinker")
            .defaultHeader("ET-Client-ID", "servicelinker")
            .defaultHeader("Accept", "application/xml")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create()
                        .followRedirect(true)
                        .responseTimeout(Duration.ofMillis(nsrProperties.timeout))
                )
            )
            .build()
    }

    suspend fun getQuayCoordinates(quayId: String): Coordinate? {
        if (nsrProperties.cacheCoordinates) {
            coordinateCache[quayId]?.let { cached ->
                logger.debug("Using cached coordinates for {}", quayId)
                return cached
            }
        }

        return try {
            logger.debug("Looking up coordinates for quay {} from NSR", quayId)

            val responseBody = webClient.get()
                .uri("/quays/{quayId}", quayId)
                .retrieve()
                .awaitBody<String>()

            val coordinate = parseCoordinateFromXml(responseBody)

            if (coordinate != null && nsrProperties.cacheCoordinates) {
                coordinateCache[quayId] = coordinate
                logger.debug("Cached coordinates for {}: {}", quayId, coordinate)
            }

            coordinate
        } catch (e: Exception) {
            logger.warn("Failed to get coordinates for quay {}: {}", quayId, e.message)
            null
        }
    }

    private fun parseCoordinateFromXml(xml: String): Coordinate? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

            val centroid = document.getElementsByTagName("Centroid").item(0) as? Element ?: return null
            val location = centroid.getElementsByTagName("Location").item(0) as? Element ?: return null

            val latitude = getElementText(location, "Latitude")?.toBigDecimalOrNull()
            val longitude = getElementText(location, "Longitude")?.toBigDecimalOrNull()

            if (latitude != null && longitude != null) {
                Coordinate(latitude, longitude)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse NSR XML response: {}", e.message)
            null
        }
    }

    private fun getElementText(parent: Element, tagName: String): String? {
        val elements = parent.getElementsByTagName(tagName)
        return if (elements.length > 0) {
            elements.item(0).textContent?.trim()
        } else null
    }

    fun getCacheSize(): Int = coordinateCache.size
}

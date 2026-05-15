package no.entur.servicelinker.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import no.entur.servicelinker.model.Coordinate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.io.StringReader
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
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
    val maxConcurrentRequests: Int = 32,
    val maxRetries: Int = 2,
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
            // Per https://developer.entur.org — header must follow "company-application".
            .defaultHeader("ET-Client-Name", "entur-servicelinker")
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
        if (nsrProperties.cacheCoordinates) coordinateCache.getIfPresent(quayId)?.let { return it }
        val coord = fetchWithRetry(quayId) ?: return null
        if (nsrProperties.cacheCoordinates) coordinateCache.put(quayId, coord)
        return coord
    }

    /** Parallel fan-out with bounded concurrency. Missing keys = 404 / exhausted retries. */
    suspend fun getQuayCoordinates(quayIds: Collection<String>): Map<String, Coordinate> {
        if (quayIds.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<String, Coordinate>()
        val semaphore = Semaphore(nsrProperties.maxConcurrentRequests)
        coroutineScope {
            quayIds
                .distinct()
                .map { id ->
                    async {
                        semaphore.withPermit { getQuayCoordinates(id)?.also { results[id] = it } }
                    }
                }.awaitAll()
        }
        return results
    }

    /** Retry on 5xx/429/network errors with linear backoff; 404 and other 4xx return null fast. */
    private suspend fun fetchWithRetry(quayId: String): Coordinate? {
        repeat(nsrProperties.maxRetries) { attempt ->
            try {
                val body =
                    webClient
                        .get()
                        .uri("/quays/{quayId}", quayId)
                        .retrieve()
                        .awaitBody<String>()
                return parseCoordinateFromXml(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebClientResponseException) {
                val status = e.statusCode.value()
                if (status == 404) return null
                if (status in 400..499 && status != 429) {
                    logger.warn("Quay {} got HTTP {} — not retrying", quayId, status)
                    return null
                }
                if (attempt == nsrProperties.maxRetries - 1) {
                    logger.warn("Quay {} failed after {} attempts: HTTP {}", quayId, nsrProperties.maxRetries, status)
                    return null
                }
                delay(BASE_RETRY_DELAY_MS * (attempt + 1))
            } catch (e: Exception) {
                if (attempt == nsrProperties.maxRetries - 1) {
                    logger.warn("Quay {} failed after {} attempts: {}", quayId, nsrProperties.maxRetries, e.message)
                    return null
                }
                delay(BASE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return null
    }

    private fun parseCoordinateFromXml(xml: String): Coordinate? =
        try {
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

    fun getCacheSize(): Long = coordinateCache.estimatedSize()

    private companion object {
        const val BASE_RETRY_DELAY_MS = 500L
        val xmlInputFactory: XMLInputFactory = XMLInputFactory.newInstance()
    }
}

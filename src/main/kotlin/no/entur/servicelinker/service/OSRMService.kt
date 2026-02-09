package no.entur.servicelinker.service

import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMResponse
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.TransportMode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@ConfigurationProperties(prefix = "osrm")
data class OsrmProperties(
    val urls: Map<String, String> = emptyMap(),
    val timeout: Long = 30000,
    val cacheRoutes: Boolean = true
)

@Service
class OSRMService(
    private val osrmProperties: OsrmProperties
) {
    private val logger = LoggerFactory.getLogger(OSRMService::class.java)
    private val webClients = ConcurrentHashMap<String, WebClient>()
    private val routeCache = ConcurrentHashMap<String, OSRMRoute>()

    private fun getWebClient(baseUrl: String): WebClient {
        return webClients.computeIfAbsent(baseUrl) {
            WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(
                    ReactorClientHttpConnector(
                        HttpClient.create()
                            .followRedirect(true)
                            .responseTimeout(Duration.ofMillis(osrmProperties.timeout))
                    )
                )
                .build()
        }
    }

    private fun getBaseUrl(transportMode: TransportMode?): String {
        val key = when (transportMode) {
            TransportMode.BUS, TransportMode.COACH -> "bus"
            TransportMode.RAIL, TransportMode.TRAM, TransportMode.METRO -> "rail"
            TransportMode.FERRY -> "ferry"
            TransportMode.WATER -> "water"
            else -> "default"
        }
        return osrmProperties.urls[key]
            ?: osrmProperties.urls["default"]
            ?: error("No OSRM URL configured for mode '$key' and no default URL set")
    }

    suspend fun calculateRoute(from: Coordinate, to: Coordinate, transportMode: TransportMode?): OSRMRoute? {
        val modeKey = transportMode?.name ?: "DEFAULT"
        val cacheKey = if (osrmProperties.cacheRoutes) "$modeKey:${from.longitude},${from.latitude}-${to.longitude},${to.latitude}" else null

        cacheKey?.let { key ->
            routeCache[key]?.let { cachedRoute ->
                logger.debug("Using cached route for $key")
                return cachedRoute
            }
        }

        return try {
            val baseUrl = getBaseUrl(transportMode)
            val webClient = getWebClient(baseUrl)
            val coordinates = "$from;$to"

            logger.debug("Calculating route from $from to $to using OSRM at $baseUrl (mode: $modeKey)")

            val response = webClient.get()
                .uri("/route/v1/driving/{coordinates}?alternatives=false&steps=false&overview=full&geometries=polyline") { uriBuilder ->
                    uriBuilder.build(coordinates)
                }
                .retrieve()
                .awaitBody<OSRMResponse>()

            val route = response.routes.firstOrNull()

            if (route != null && cacheKey != null) {
                routeCache[cacheKey] = route
                logger.debug("Cached route for $cacheKey, distance: ${route.distance}m")
            }

            route
        } catch (e: Exception) {
            logger.error("Failed to calculate route from $from to $to", e)
            null
        }
    }

    fun clearCache() {
        routeCache.clear()
        logger.info("Route cache cleared")
    }

    fun getCacheSize(): Int = routeCache.size
}

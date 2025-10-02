package no.entur.servicelinker.service

import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMResponse
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.TransportMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class OSRMService(
    @Value("\${osrm.base-url}") private val osrmBaseUrl: String,
    @Value("\${osrm.timeout}") private val timeoutMs: Long,
    @Value("\${servicelink.cache-routes}") private val cacheRoutes: Boolean
) {
    private val logger = LoggerFactory.getLogger(OSRMService::class.java)
    private val webClient = WebClient.builder()
        .baseUrl(osrmBaseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .followRedirect(true)
                    .responseTimeout(Duration.ofMillis(timeoutMs))
            )
        )
        .build()

    private val routeCache = ConcurrentHashMap<String, OSRMRoute>()

    suspend fun calculateRoute(from: Coordinate, to: Coordinate, transportMode: TransportMode?): OSRMRoute? {
        val cacheKey = if (cacheRoutes) "${from.longitude},${from.latitude}-${to.longitude},${to.latitude}" else null

        cacheKey?.let { key ->
            routeCache[key]?.let { cachedRoute ->
                logger.debug("Using cached route for $key")
                return cachedRoute
            }
        }

        return try {
            val profile = getOSRMProfile(transportMode)
            val coordinates = "$from;$to"

            logger.debug("Calculating route from $from to $to using profile $profile")

            val response = webClient.get()
                .uri("/route/v1/$profile/{coordinates}?alternatives=false&steps=false&overview=full&geometries=polyline") { uriBuilder ->
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

    private fun getOSRMProfile(transportMode: TransportMode?): String {
        return when (transportMode) {
            TransportMode.BUS, TransportMode.COACH -> "driving"
            TransportMode.TRAM, TransportMode.METRO -> "driving"
            TransportMode.RAIL -> "driving"
            TransportMode.FERRY -> "driving"
            else -> "driving"
        }
    }

    fun clearCache() {
        routeCache.clear()
        logger.info("Route cache cleared")
    }

    fun getCacheSize(): Int = routeCache.size
}

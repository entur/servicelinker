package no.entur.servicelinker.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMResponse
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.TransportMode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "osrm")
data class OsrmProperties(
    val urls: Map<String, String> = emptyMap(),
    val timeout: Long = 30000,
    val cacheRoutes: Boolean = true,
    val cacheMaxSize: Int = 40_000,
    val cacheTtlHours: Int = 24,
)

@Service
class OSRMService(
    private val osrmProperties: OsrmProperties,
) {
    private val logger = LoggerFactory.getLogger(OSRMService::class.java)
    private val webClients = ConcurrentHashMap<String, WebClient>()

    // LRU cache: ~2 KB avg per entry (polyline geometry), 40k entries ≈ 80 MB; entries expire after 24h
    private val routeCache: Cache<String, OSRMRoute> =
        Caffeine
            .newBuilder()
            .maximumSize(osrmProperties.cacheMaxSize.toLong())
            .expireAfterWrite(osrmProperties.cacheTtlHours.toLong(), TimeUnit.HOURS)
            .build()

    private fun getWebClient(baseUrl: String): WebClient =
        webClients.computeIfAbsent(baseUrl) {
            WebClient
                .builder()
                .baseUrl(baseUrl)
                .clientConnector(
                    ReactorClientHttpConnector(
                        HttpClient
                            .create()
                            .followRedirect(true)
                            .responseTimeout(Duration.ofMillis(osrmProperties.timeout)),
                    ),
                ).build()
        }

    private fun getBaseUrl(transportMode: TransportMode?): String {
        val key =
            when (transportMode) {
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

    suspend fun calculateRoute(
        from: Coordinate,
        to: Coordinate,
        transportMode: TransportMode?,
    ): OSRMRoute? {
        val modeKey = transportMode?.name ?: "DEFAULT"
        val cacheKey =
            if (osrmProperties.cacheRoutes) {
                "$modeKey:${from.longitude},${from.latitude}-${to.longitude},${to.latitude}"
            } else {
                null
            }

        cacheKey?.let { key ->
            routeCache.getIfPresent(key)?.let { cachedRoute ->
                logger.debug("Using cached route for $key")
                return cachedRoute
            }
        }

        val baseUrl = getBaseUrl(transportMode)
        return try {
            val webClient = getWebClient(baseUrl)
            val coordinates = "$from;$to"

            val overview = if (from.haversineDistanceTo(to) > SIMPLIFIED_THRESHOLD_METERS) "simplified" else "full"
            logger.debug("Calculating route from $from to $to using OSRM at $baseUrl (mode: $modeKey, overview: $overview)")

            val response =
                webClient
                    .get()
                    .uri(
                        "/route/v1/driving/{coordinates}?alternatives=false&steps=false&overview={overview}&geometries=polyline",
                    ) { uriBuilder ->
                        uriBuilder.build(coordinates, overview)
                    }.retrieve()
                    .awaitBody<OSRMResponse>()

            val route = response.routes.firstOrNull()

            if (route != null && cacheKey != null) {
                routeCache.put(cacheKey, route)
                logger.debug("Cached route for $cacheKey, distance: ${route.distance}m")
            }

            route
        } catch (e: CancellationException) {
            // Kotlin's CancellationException extends IllegalStateException, so the catch-all below
            // would swallow it and report a cancelled run as "no route" for every remaining pair.
            throw e
        } catch (e: WebClientResponseException) {
            // OSRM puts the interesting part in the body, not the status: `NoSegment` means a
            // coordinate is off the loaded network (wrong graph, or a bad quay coordinate),
            // `NoRoute` means the graph is disconnected between the two points. Without the body
            // a 400 is indistinguishable between the two.
            logger.warn(
                "OSRM {} for {} → {} (mode: {}, instance: {}): {}",
                e.statusCode,
                from,
                to,
                modeKey,
                baseUrl,
                e.responseBodyAsString.take(MAX_LOGGED_ERROR_BODY).ifBlank { e.message },
            )
            null
        } catch (e: Exception) {
            logger.warn("Failed to calculate route from $from to $to (mode: $modeKey, instance: $baseUrl): ${e.message}")
            null
        }
    }

    fun clearCache() {
        routeCache.invalidateAll()
        logger.info("Route cache cleared")
    }

    fun getCacheSize(): Long = routeCache.estimatedSize()

    companion object {
        const val SIMPLIFIED_THRESHOLD_METERS = 30_000.0
        const val MAX_LOGGED_ERROR_BODY = 500
    }
}

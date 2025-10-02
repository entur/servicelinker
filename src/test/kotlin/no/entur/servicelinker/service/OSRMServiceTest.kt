package no.entur.servicelinker.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.TransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OSRMServiceTest {

    private lateinit var wireMockServer: WireMockServer
    private lateinit var osrmService: OSRMService

    @BeforeEach
    fun setUp() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().port(8089))
        wireMockServer.start()

        osrmService = OSRMService(
            osrmBaseUrl = "http://localhost:8089",
            timeoutMs = 5000,
            cacheRoutes = false
        )
    }

    @AfterEach
    fun tearDown() {
        wireMockServer.stop()
    }

    @Test
    fun `should calculate route using real OSRM response data`() = runTest {
        val resource = ClassPathResource("sample-osrm-polyline.json")
        val rawOsrmJson = resource.inputStream.bufferedReader().use { it.readText() }

        wireMockServer.stubFor(
            get(urlPathMatching("/route/v1/driving/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(rawOsrmJson)
                )
        )

        val from = Coordinate(59.913953, 10.75261)
        val to = Coordinate(59.955648, 11.046175)

        val route = osrmService.calculateRoute(from, to, TransportMode.BUS)

        println("Route: $route")
        if (route != null) {
            println("Distance: ${route.distance}")
            println("Duration: ${route.duration}")
            println("Coordinates size: ${route.coordinates.size}")
        }

        assertNotNull(route)
        assertEquals(20842.6, route.distance)
        assertEquals(1341.1, route.duration)

        assertNotNull(route.coordinates)
        assert(route.coordinates.isNotEmpty())


        val firstCoordinate = route.coordinates.first()
        val lastCoordinate = route.coordinates.last()

        assert((firstCoordinate[0].toDouble() - 10.75261) < 0.0001) { "Expected longitude ~10.75261, got ${firstCoordinate[0]}" }
        assert((firstCoordinate[1].toDouble() - 59.913953) < 0.0001) { "Expected latitude ~59.913953, got ${firstCoordinate[1]}" }
        assert((lastCoordinate[0].toDouble() - 11.046175) < 0.0001) { "Expected longitude ~11.046175, got ${lastCoordinate[0]}" }
        assert((lastCoordinate[1].toDouble() - 59.955648) < 0.0001) { "Expected latitude ~59.955648, got ${lastCoordinate[1]}" }

        wireMockServer.verify(
            getRequestedFor(urlPathMatching("/route/v1/driving/.*"))
        )
    }

    @Test
    fun `should initialize OSRM service with correct cache behavior`() {
        assertEquals(0, osrmService.getCacheSize())

        osrmService.clearCache()
        assertEquals(0, osrmService.getCacheSize())
    }
}

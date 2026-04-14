package no.entur.servicelinker.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.TransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OSRMServiceTest {
    private lateinit var osrmService: OSRMService

    private val busPort = 8089
    private val railPort = 8090

    private lateinit var busWireMock: WireMockServer
    private lateinit var railWireMock: WireMockServer

    private val oslo = Coordinate(59.913953, 10.75261)
    private val lillestrøm = Coordinate(59.955648, 11.046175)

    @BeforeEach
    fun setUp() {
        busWireMock = WireMockServer(WireMockConfiguration.options().port(busPort))
        railWireMock = WireMockServer(WireMockConfiguration.options().port(railPort))
        busWireMock.start()
        railWireMock.start()

        val properties =
            OsrmProperties(
                urls =
                    mapOf(
                        "bus" to "http://localhost:$busPort",
                        "rail" to "http://localhost:$railPort",
                        "default" to "http://localhost:$busPort",
                    ),
                timeout = 5000,
                cacheRoutes = false,
            )

        osrmService = OSRMService(properties)
    }

    @AfterEach
    fun tearDown() {
        busWireMock.stop()
        railWireMock.stop()
    }

    private fun stubOsrmResponse(server: WireMockServer) {
        val resource = ClassPathResource("sample-osrm-polyline.json")
        val rawOsrmJson = resource.inputStream.bufferedReader().use { it.readText() }

        server.stubFor(
            get(urlPathMatching("/route/v1/driving/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(rawOsrmJson),
                ),
        )
    }

    @Test
    fun `should calculate route using real OSRM response data`() =
        runTest {
            stubOsrmResponse(busWireMock)

            val route = osrmService.calculateRoute(oslo, lillestrøm, TransportMode.BUS)

            assertNotNull(route)
            assertEquals(20842.6, route.distance)
            assertEquals(1341.1, route.duration)

            assertNotNull(route.coordinates)
            assertTrue(route.coordinates.isNotEmpty())

            val firstCoordinate = route.coordinates.first()
            val lastCoordinate = route.coordinates.last()

            assertTrue((firstCoordinate[0].toDouble() - 10.75261) < 0.0001) { "Expected longitude ~10.75261, got ${firstCoordinate[0]}" }
            assertTrue((firstCoordinate[1].toDouble() - 59.913953) < 0.0001) { "Expected latitude ~59.913953, got ${firstCoordinate[1]}" }
            assertTrue((lastCoordinate[0].toDouble() - 11.046175) < 0.0001) { "Expected longitude ~11.046175, got ${lastCoordinate[0]}" }
            assertTrue((lastCoordinate[1].toDouble() - 59.955648) < 0.0001) { "Expected latitude ~59.955648, got ${lastCoordinate[1]}" }

            busWireMock.verify(
                getRequestedFor(urlPathMatching("/route/v1/driving/.*")),
            )
        }

    @Test
    fun `should route BUS mode to bus OSRM instance`() =
        runTest {
            stubOsrmResponse(busWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.BUS)
            busWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            railWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route COACH mode to bus OSRM instance`() =
        runTest {
            stubOsrmResponse(busWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.COACH)
            busWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            railWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route RAIL mode to rail OSRM instance`() =
        runTest {
            stubOsrmResponse(railWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.RAIL)
            railWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            busWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route TRAM mode to rail OSRM instance`() =
        runTest {
            stubOsrmResponse(railWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.TRAM)
            railWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            busWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route METRO mode to rail OSRM instance`() =
        runTest {
            stubOsrmResponse(railWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.METRO)
            railWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            busWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route unknown mode to default OSRM instance`() =
        runTest {
            stubOsrmResponse(busWireMock) // default maps to busPort in our test config
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.UNKNOWN)
            busWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should route null mode to default OSRM instance`() =
        runTest {
            stubOsrmResponse(busWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, null)
            busWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should include transport mode in cache key`() =
        runTest {
            val properties =
                OsrmProperties(
                    urls =
                        mapOf(
                            "bus" to "http://localhost:$busPort",
                            "rail" to "http://localhost:$railPort",
                            "default" to "http://localhost:$busPort",
                        ),
                    timeout = 5000,
                    cacheRoutes = true,
                )
            val cachingService = OSRMService(properties)

            stubOsrmResponse(busWireMock)
            stubOsrmResponse(railWireMock)

            cachingService.calculateRoute(oslo, lillestrøm, TransportMode.BUS)
            cachingService.calculateRoute(oslo, lillestrøm, TransportMode.RAIL)

            busWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            railWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))

            assertEquals(2L, cachingService.getCacheSize())
        }

    @Test
    fun `should initialize OSRM service with correct cache behavior`() {
        assertEquals(0L, osrmService.getCacheSize())
        osrmService.clearCache()
        assertEquals(0L, osrmService.getCacheSize())
    }
}

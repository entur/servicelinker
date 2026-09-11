package no.entur.servicelinker.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.TransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OSRMServiceTest {
    private lateinit var osrmService: OSRMService

    private lateinit var busWireMock: WireMockServer
    private lateinit var railWireMock: WireMockServer
    private lateinit var ferryWireMock: WireMockServer
    private lateinit var waterWireMock: WireMockServer

    private val oslo = Coordinate(59.913953, 10.75261)
    private val lillestrøm = Coordinate(59.955648, 11.046175)

    // Open-coast hop with no mapped ferry way between the two, so the real water graph answers
    // NoRoute for it: one of the pairs behind the September 2026 NOR-WAY report.
    private val nesna = Coordinate(66.200378, 13.008075)
    private val sandnessjøen = Coordinate(66.024, 12.639)

    @BeforeEach
    fun setUp() {
        // Dynamic ports: fixed ones collide with the other WireMock-based tests as soon as
        // anything runs the suite in parallel.
        busWireMock = startWireMock()
        railWireMock = startWireMock()
        ferryWireMock = startWireMock()
        waterWireMock = startWireMock()

        val properties =
            OsrmProperties(
                urls =
                    mapOf(
                        "bus" to busWireMock.baseUrl(),
                        "rail" to railWireMock.baseUrl(),
                        "ferry" to ferryWireMock.baseUrl(),
                        "water" to waterWireMock.baseUrl(),
                        "default" to busWireMock.baseUrl(),
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
        ferryWireMock.stop()
        waterWireMock.stop()
    }

    private fun startWireMock() = WireMockServer(WireMockConfiguration.options().dynamicPort()).apply { start() }

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

            assertTrue(route.path.isNotEmpty())

            val firstCoordinate = route.path.first()
            val lastCoordinate = route.path.last()

            assertTrue((firstCoordinate.longitude.toDouble() - 10.75261) < 0.0001) { "Expected longitude ~10.75261, got ${firstCoordinate.longitude}" }
            assertTrue((firstCoordinate.latitude.toDouble() - 59.913953) < 0.0001) { "Expected latitude ~59.913953, got ${firstCoordinate.latitude}" }
            assertTrue((lastCoordinate.longitude.toDouble() - 11.046175) < 0.0001) { "Expected longitude ~11.046175, got ${lastCoordinate.longitude}" }
            assertTrue((lastCoordinate.latitude.toDouble() - 59.955648) < 0.0001) { "Expected latitude ~59.955648, got ${lastCoordinate.latitude}" }

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
    fun `should route FERRY mode to ferry OSRM instance`() =
        runTest {
            stubOsrmResponse(ferryWireMock)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.FERRY)
            ferryWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            waterWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    /**
     * Covers our own mode→instance mapping only. In prd both keys point at the same graph, but they
     * are separate properties and WATER must follow `osrm.urls.water`.
     */
    @Test
    fun `should route WATER mode to water OSRM instance, never ferry`() =
        runTest {
            stubOsrmResponse(waterWireMock)
            osrmService.calculateRoute(nesna, sandnessjøen, TransportMode.WATER)
            waterWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            ferryWireMock.verify(0, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
        }

    @Test
    fun `should fall back to null and log the OSRM error body on a 400`() =
        runTest {
            val padding = "x".repeat(1000)
            waterWireMock.stubFor(
                get(urlPathMatching("/route/v1/driving/.*"))
                    .willReturn(
                        aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"code":"NoSegment","message":"$padding"}"""),
                    ),
            )

            val warning = captureOsrmWarning { assertNull(osrmService.calculateRoute(nesna, sandnessjøen, TransportMode.WATER)) }

            waterWireMock.verify(1, getRequestedFor(urlPathMatching("/route/v1/driving/.*")))
            // The body is the diagnostic: without it a 400 is indistinguishable from NoRoute.
            assertTrue(warning.contains("NoSegment"), warning)
            assertTrue(warning.contains(waterWireMock.baseUrl()), warning)
            assertFalse(warning.contains(padding), "response body must be truncated to MAX_LOGGED_ERROR_BODY")
        }

    /** Runs [block] with a logback appender on OSRMService and returns its single WARN message. */
    private suspend fun captureOsrmWarning(block: suspend () -> Unit): String {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(OSRMService::class.java) as Logger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.single { it.level == Level.WARN }.formattedMessage
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
    fun `should always request the full geometry, whatever the distance`() =
        runTest {
            stubOsrmResponse(busWireMock)

            // OSRM's own overview=simplified targets a near-constant point count, so a long leg
            // comes back kilometres off the road. We ask for everything and simplify ourselves.
            val kirkenes = Coordinate(69.7269, 30.0454)
            osrmService.calculateRoute(oslo, lillestrøm, TransportMode.BUS)
            osrmService.calculateRoute(oslo, kirkenes, TransportMode.BUS)

            busWireMock.verify(
                2,
                getRequestedFor(urlPathMatching("/route/v1/driving/.*"))
                    .withQueryParam("overview", equalTo("full")),
            )
        }

    @Test
    fun `should include transport mode in cache key`() =
        runTest {
            val properties =
                OsrmProperties(
                    urls =
                        mapOf(
                            "bus" to busWireMock.baseUrl(),
                            "rail" to railWireMock.baseUrl(),
                            "default" to busWireMock.baseUrl(),
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

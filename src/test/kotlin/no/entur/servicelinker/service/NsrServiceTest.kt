package no.entur.servicelinker.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class NsrServiceTest {
    private lateinit var nsrService: NsrService
    private lateinit var wireMock: WireMockServer
    private val meters = ServicelinkerMetrics(SimpleMeterRegistry())
    private var port = 0

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMock.start()
        port = wireMock.port()

        val properties =
            NsrProperties(
                baseUrl = "http://localhost:$port",
                timeout = 5000,
                cacheCoordinates = false,
                maxRetries = 1,
                perRequestDeadlineMs = 0, // disable; runTest's virtual clock fires this ahead of real I/O
            )
        nsrService = NsrService(properties, meters)
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    @Test
    fun `should extract coordinates from NSR quay response`() =
        runTest {
            val nsrXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Quay xmlns="http://www.netex.org.uk/netex" id="NSR:Quay:102403" version="1">
                    <Name>Bergen</Name>
                    <Centroid>
                        <Location>
                            <Longitude>5.328240</Longitude>
                            <Latitude>60.393604</Latitude>
                        </Location>
                    </Centroid>
                </Quay>
                """.trimIndent()

            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A102403"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/xml")
                            .withBody(nsrXml),
                    ),
            )

            val coordinate = requireNotNull(nsrService.getQuayCoordinates("NSR:Quay:102403"))

            assertEquals(BigDecimal("60.393604"), coordinate.latitude)
            assertEquals(BigDecimal("5.328240"), coordinate.longitude)

            wireMock.verify(
                getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A102403"))
                    .withHeader("ET-Client-Name", equalTo("entur-servicelinker")),
            )
        }

    @Test
    fun `should return null when NSR returns 404`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A999999"))
                    .willReturn(aResponse().withStatus(404)),
            )

            val coordinate = nsrService.getQuayCoordinates("NSR:Quay:999999")

            assertNull(coordinate)
        }

    @Test
    fun `should return null when NSR response has no coordinates`() =
        runTest {
            val nsrXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Quay xmlns="http://www.netex.org.uk/netex" id="NSR:Quay:100" version="1">
                    <Name>Empty Quay</Name>
                </Quay>
                """.trimIndent()

            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A100"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/xml")
                            .withBody(nsrXml),
                    ),
            )

            val coordinate = nsrService.getQuayCoordinates("NSR:Quay:100")

            assertNull(coordinate)
        }

    @Test
    fun `should cache coordinates when enabled`() =
        runTest {
            val properties =
                NsrProperties(
                    baseUrl = "http://localhost:$port",
                    timeout = 5000,
                    cacheCoordinates = true,
                    perRequestDeadlineMs = 0,
                )
            val cachingService = NsrService(properties, meters)

            val nsrXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Quay xmlns="http://www.netex.org.uk/netex" id="NSR:Quay:102403" version="1">
                    <Centroid>
                        <Location>
                            <Longitude>5.328240</Longitude>
                            <Latitude>60.393604</Latitude>
                        </Location>
                    </Centroid>
                </Quay>
                """.trimIndent()

            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A102403"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/xml")
                            .withBody(nsrXml),
                    ),
            )

            // First call hits the API
            val first = cachingService.getQuayCoordinates("NSR:Quay:102403")
            assertNotNull(first)

            // Second call should use cache
            val second = cachingService.getQuayCoordinates("NSR:Quay:102403")
            assertNotNull(second)
            assertEquals(first, second)

            // Should only have made one HTTP request
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A102403")))
            assertEquals(1L, cachingService.getCacheSize())
        }

    @Test
    fun `should handle connection timeout gracefully`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Atimeout"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withFixedDelay(10000),
                    ),
            )

            val properties =
                NsrProperties(
                    baseUrl = "http://localhost:$port",
                    timeout = 100,
                    cacheCoordinates = false,
                    maxRetries = 1,
                    perRequestDeadlineMs = 0,
                )
            val timeoutService = NsrService(properties, meters)

            val coordinate = timeoutService.getQuayCoordinates("NSR:Quay:timeout")

            assertNull(coordinate)
        }

    @Test
    fun `should retry on 503 and then succeed`() =
        runTest {
            val nsrXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Quay xmlns="http://www.netex.org.uk/netex" id="NSR:Quay:retry" version="1">
                    <Centroid><Location><Longitude>10.0</Longitude><Latitude>60.0</Latitude></Location></Centroid>
                </Quay>
                """.trimIndent()
            val scenario = "retry-then-succeed"
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Aretry"))
                    .inScenario(scenario)
                    .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("second"),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Aretry"))
                    .inScenario(scenario)
                    .whenScenarioStateIs("second")
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/xml")
                            .withBody(nsrXml),
                    ),
            )

            val service =
                NsrService(
                    NsrProperties(
                        baseUrl = "http://localhost:$port",
                        timeout = 5000,
                        cacheCoordinates = false,
                        maxRetries = 3,
                        retryBaseDelayMs = 1,
                        retryMaxDelayMs = 5,
                        perRequestDeadlineMs = 0,
                    ),
                    meters,
                )

            val coordinate = service.getQuayCoordinates("NSR:Quay:retry")

            assertNotNull(coordinate)
            assertEquals(BigDecimal("60.0"), coordinate!!.latitude)
            wireMock.verify(2, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3Aretry")))
        }

    @Test
    fun `should not retry on 404`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Agone"))
                    .willReturn(aResponse().withStatus(404)),
            )

            val service =
                NsrService(
                    NsrProperties(
                        baseUrl = "http://localhost:$port",
                        timeout = 5000,
                        cacheCoordinates = false,
                        maxRetries = 4,
                        retryBaseDelayMs = 1,
                        retryMaxDelayMs = 5,
                        perRequestDeadlineMs = 0,
                    ),
                    meters,
                )

            val coordinate = service.getQuayCoordinates("NSR:Quay:gone")

            assertNull(coordinate)
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3Agone")))
        }

    @Test
    fun `batch should resolve mix of cached and uncached, dedup quay IDs`() =
        runTest {
            val xmlFor = { lon: String, lat: String ->
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Quay xmlns="http://www.netex.org.uk/netex">
                    <Centroid><Location><Longitude>$lon</Longitude><Latitude>$lat</Latitude></Location></Centroid>
                </Quay>
                """.trimIndent()
            }
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A1"))
                    .willReturn(aResponse().withStatus(200).withBody(xmlFor("1.0", "60.0"))),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A2"))
                    .willReturn(aResponse().withStatus(200).withBody(xmlFor("2.0", "61.0"))),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3A3"))
                    .willReturn(aResponse().withStatus(404)),
            )

            val service =
                NsrService(
                    NsrProperties(
                        baseUrl = "http://localhost:$port",
                        timeout = 5000,
                        cacheCoordinates = true,
                        maxRetries = 1,
                        perRequestDeadlineMs = 0,
                    ),
                    meters,
                )

            // First call warms cache for Quay:1
            assertNotNull(service.getQuayCoordinates("NSR:Quay:1"))
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A1")))

            // Batch with duplicate Quay:2 + already-cached Quay:1 + missing Quay:3
            val results = service.getQuayCoordinates(listOf("NSR:Quay:1", "NSR:Quay:2", "NSR:Quay:2", "NSR:Quay:3"))

            assertEquals(2, results.size)
            assertNotNull(results["NSR:Quay:1"])
            assertNotNull(results["NSR:Quay:2"])
            assertNull(results["NSR:Quay:3"])

            // Quay:1 not refetched (cached); Quay:2 called once despite duplicate; Quay:3 called once
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A1")))
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A2")))
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3A3")))
        }

    @Test
    fun `separates transient 429 failures from a definitive 404`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Athrottled"))
                    .willReturn(aResponse().withStatus(429)),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Agone"))
                    .willReturn(aResponse().withStatus(404)),
            )

            val service =
                NsrService(
                    NsrProperties(
                        baseUrl = "http://localhost:$port",
                        timeout = 5000,
                        cacheCoordinates = false,
                        maxRetries = 2,
                        retryBaseDelayMs = 1,
                        retryMaxDelayMs = 5,
                        perRequestDeadlineMs = 0,
                    ),
                    meters,
                )

            val result = service.lookupQuayCoordinates(listOf("NSR:Quay:throttled", "NSR:Quay:gone"))

            assertTrue(result.coordinates.isEmpty())
            assertEquals(setOf("NSR:Quay:throttled"), result.transientFailures)
        }

    @Test
    fun `treats an unparseable 200 as transient, not as an absent quay`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Atruncated"))
                    .willReturn(aResponse().withStatus(200).withBody("<Quay><Centroid>")),
            )

            val result = nsrService.lookupQuayCoordinates(listOf("NSR:Quay:truncated"))

            assertEquals(setOf("NSR:Quay:truncated"), result.transientFailures)
        }

    /**
     * The semaphore is process-wide, so two overlapping batches share the budget. Counting inside
     * the handler (rather than timing the batches) keeps this deterministic, and one quay is made
     * to retry so the permit release/reacquire around the backoff is covered too.
     */
    @Test
    fun `bounds in-flight requests across overlapping batches, including over retries`() =
        runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val hits = ConcurrentHashMap<String, Int>()
            val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            server.executor = Executors.newFixedThreadPool(8)
            server.createContext("/quays") { exchange ->
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    val path = exchange.requestURI.path
                    val attempt = hits.merge(path, 1, Int::plus)
                    // First hit on quay 1 fails transiently, forcing a retry with backoff.
                    val body = if (path.endsWith("1") && attempt == 1) null else QUAY_XML.toByteArray()
                    Thread.sleep(50)
                    exchange.sendResponseHeaders(if (body == null) 503 else 200, body?.size?.toLong() ?: -1L)
                    body?.let { exchange.responseBody.use { out -> out.write(it) } }
                } finally {
                    inFlight.decrementAndGet()
                    exchange.close()
                }
            }
            server.start()
            try {
                val service =
                    NsrService(
                        NsrProperties(
                            baseUrl = "http://localhost:${server.address.port}",
                            timeout = 5000,
                            cacheCoordinates = false,
                            maxConcurrentRequests = 2,
                            maxRetries = 3,
                            retryBaseDelayMs = 1,
                            retryMaxDelayMs = 5,
                            perRequestDeadlineMs = 0,
                        ),
                        meters,
                    )
                val ids = (1..6).map { "NSR:Quay:$it" }
                coroutineScope {
                    launch { service.lookupQuayCoordinates(ids.take(3)) }
                    launch { service.lookupQuayCoordinates(ids.drop(3)) }
                }

                assertEquals(2, peak.get(), "Two batches must share the process-wide concurrency budget")
                assertEquals(7, hits.values.sum(), "6 quays plus the one 503 retry")
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `treats 403 as transient without retrying it`() =
        runTest {
            wireMock.stubFor(
                get(urlPathEqualTo("/quays/NSR%3AQuay%3Aforbidden"))
                    .willReturn(aResponse().withStatus(403)),
            )

            val result = nsrService.lookupQuayCoordinates(listOf("NSR:Quay:forbidden"))

            assertEquals(setOf("NSR:Quay:forbidden"), result.transientFailures)
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/quays/NSR%3AQuay%3Aforbidden")))
        }

    private companion object {
        val QUAY_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Quay xmlns="http://www.netex.org.uk/netex">
                <Centroid><Location><Longitude>10.0</Longitude><Latitude>60.0</Latitude></Location></Centroid>
            </Quay>
            """.trimIndent()
    }
}

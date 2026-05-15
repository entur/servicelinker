package no.entur.servicelinker.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NsrServiceTest {
    private lateinit var nsrService: NsrService
    private lateinit var wireMock: WireMockServer
    private val port = 8091

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(WireMockConfiguration.options().port(port))
        wireMock.start()

        val properties =
            NsrProperties(
                baseUrl = "http://localhost:$port",
                timeout = 5000,
                cacheCoordinates = false,
                maxRetries = 1,
            )
        nsrService = NsrService(properties)
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
                )
            val cachingService = NsrService(properties)

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
                )
            val timeoutService = NsrService(properties)

            val coordinate = timeoutService.getQuayCoordinates("NSR:Quay:timeout")

            assertNull(coordinate)
        }
}

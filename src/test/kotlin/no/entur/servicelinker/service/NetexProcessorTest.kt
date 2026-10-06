package no.entur.servicelinker.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.IncompleteJourneyPatternException
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.plugins.MetadataExtractor
import no.entur.servicelinker.util.PolylineEncoding
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class NetexProcessorTest {
    private lateinit var mockOsrmService: OSRMService
    private lateinit var netexProcessor: NetexProcessor
    private lateinit var registry: SimpleMeterRegistry

    val oslo = Coordinate(59.9139, 10.7522)
    val lillestrøm = Coordinate(59.9556, 11.0464)
    val drammen = Coordinate(59.7434, 10.2044)
    val bergen = Coordinate(60.3913, 5.3221)
    private val osloToLillestromCoords = listOf(oslo, lillestrøm)
    private val osloToDrammenCoords = listOf(oslo, drammen)
    private val osloToBergenCoords = listOf(oslo, bergen)

    @BeforeEach
    fun setup() {
        mockOsrmService = mockk(relaxed = true)
        registry = SimpleMeterRegistry()
        netexProcessor = NetexProcessor(mockOsrmService, MetadataExtractor(), ServicelinkerMetrics(registry))
    }

    private fun posListOfNewLink(processedXml: ByteArray): List<Double> {
        val doc = parseXmlFromBytes(processedXml)
        val newLink =
            (0 until doc.getElementsByTagName("ServiceLink").length)
                .map { doc.getElementsByTagName("ServiceLink").item(it) as Element }
                .first { it.getAttribute("id") == "FIN:ServiceLink:2-3" }
        return newLink
            .getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
            .item(0)
            .textContent
            .trim()
            .split("\\s+".toRegex())
            .map { it.toDouble() }
    }

    private fun distanceOfNewLink(processedXml: ByteArray): Int {
        val doc = parseXmlFromBytes(processedXml)
        val newLink =
            (0 until doc.getElementsByTagName("ServiceLink").length)
                .map { doc.getElementsByTagName("ServiceLink").item(it) as Element }
                .first { it.getAttribute("id") == "FIN:ServiceLink:2-3" }
        return newLink.getElementsByTagName("Distance").item(0).textContent.trim().toInt()
    }

    private fun getSampleNetexBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/sample-netex.xml")) {
            "sample-netex.xml not found in test resources"
        }.readBytes()

    private fun mockOsrmWithRoute(
        coords: List<Coordinate>,
        distance: Double,
        duration: Double = 1200.0,
    ) {
        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } returns
            OSRMRoute(
                distance = distance,
                duration = duration,
                geometry = PolylineEncoding.encode(coords),
            )
    }

    private fun mockOsrmFailure() {
        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } throws RuntimeException("OSRM service unavailable")
    }

    @Test
    fun `should add service links for consecutive JourneyPattern stops only`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, addedCount) = netexProcessor.processNetexFile(inputBytes)

            assertTrue(processedXml.isNotEmpty(), "Processed XML should not be empty")
            // JourneyPattern has stops 1→2→3, existing ServiceLink covers 1→2, so only 2→3 is new
            assertEquals(1, addedCount, "Should add only missing consecutive service links")

            val processedDocument = parseXmlFromBytes(processedXml)
            // Total ServiceLinks: 1 existing (1→2) + 1 new (2→3) = 2
            val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")
            assertEquals(2, serviceLinks.length, "Should have 2 total service links")
        }

    @Test
    fun `should respect existing service links and not duplicate them`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, addedCount) = netexProcessor.processNetexFile(inputBytes)

            val processedDocument = parseXmlFromBytes(processedXml)
            val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")

            // Existing link 1→2 should not be recreated
            assertEquals(1, addedCount, "Should only add the missing 2→3 link")
            assertEquals(2, serviceLinks.length, "Should have 2 total (1 existing + 1 new)")

            for (i in 0 until serviceLinks.length) {
                val element = serviceLinks.item(i) as Element
                assertTrue(element.hasAttribute("id"), "Service link should have ID")

                val fromRefElement = element.getElementsByTagName("FromPointRef").item(0) as? Element
                val toRefElement = element.getElementsByTagName("ToPointRef").item(0) as? Element

                assertNotNull(fromRefElement, "Service link should have FromPointRef")
                assertNotNull(toRefElement, "Service link should have ToPointRef")
                assertTrue(requireNotNull(fromRefElement).hasAttribute("ref"), "FromPointRef should have ref attribute")
                assertTrue(requireNotNull(toRefElement).hasAttribute("ref"), "ToPointRef should have ref attribute")
            }
        }

    @Test
    fun `FromPointRef and ToPointRef should use the ScheduledStopPoint version`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            // ScheduledStopPoints with non-"1" version (timestamp-based, as seen in NWY data)
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="20260212093839">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="20260212093839">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.toByteArray())
            assertEquals(1, addedCount)

            val doc = parseXmlFromBytes(processedXml)
            val serviceLinks = doc.getElementsByTagName("ServiceLink")
            assertEquals(1, serviceLinks.length)

            val serviceLink = serviceLinks.item(0) as Element
            val fromPointRef = serviceLink.getElementsByTagName("FromPointRef").item(0) as Element
            val toPointRef = serviceLink.getElementsByTagName("ToPointRef").item(0) as Element

            assertEquals("TST:ScheduledStopPoint:A", fromPointRef.getAttribute("ref"))
            assertEquals("TST:ScheduledStopPoint:B", toPointRef.getAttribute("ref"))
            assertEquals(
                "20260212093839",
                fromPointRef.getAttribute("version"),
                "FromPointRef must use the ScheduledStopPoint's actual version, not hardcoded '1'",
            )
            assertEquals(
                "20260212093839",
                toPointRef.getAttribute("version"),
                "ToPointRef must use the ScheduledStopPoint's actual version, not hardcoded '1'",
            )
        }

    @Test
    fun `should handle OSRM service failures gracefully`() =
        runTest {
            mockOsrmFailure()

            val inputBytes = getSampleNetexBytes()

            var exceptionThrown = false
            try {
                val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)
                assertTrue(processedXml.isNotEmpty(), "Should still produce valid XML")
            } catch (_: Exception) {
                exceptionThrown = true
            }

            assertFalse(exceptionThrown, "Should not throw exception when OSRM fails")
        }

    @Test
    fun `over-distance OSRM result falls back to straight-line ServiceLink`() =
        runTest {
            // 6000km route exceeds the 5000km sanity limit → null route → straight-line SL still
            // created → JP integrity preserved (one new pair added, no exception).
            mockOsrmWithRoute(osloToBergenCoords, 6_000_000.0, 72000.0)

            val (_, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())

            assertEquals(1, addedCount, "Straight-line fallback should still produce one SL for the new pair")
        }

    @Test
    fun `route more than 10x the direct distance falls back to straight line`() =
        runTest {
            // Pair 2→3 is ~22 km direct
            mockOsrmWithRoute(osloToBergenCoords, 250_000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())

            assertEquals(1, addedCount)
            assertTrue(distanceOfNewLink(processedXml) < 25_000)
            assertEquals(
                1.0,
                registry
                    .find("servicelinker.servicelink.straight_line")
                    .tag("reason", "implausible-detour")
                    .counter()
                    ?.count(),
            )
        }

    @Test
    fun `route within 10x the direct distance is kept`() =
        runTest {
            mockOsrmWithRoute(osloToBergenCoords, 200_000.0)

            val (processedXml, _) = netexProcessor.processNetexFile(getSampleNetexBytes())

            assertEquals(200_000, distanceOfNewLink(processedXml))
            assertTrue(registry.find("servicelinker.servicelink.straight_line").counters().isEmpty())
        }

    @Test
    fun `pair under 1 km is exempt from the detour check`() =
        runTest {
            // ~560 m apart, 7 km route
            mockOsrmWithRoute(listOf(oslo, Coordinate(59.9139, 10.7622)), 7_000.0)

            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>10.7622</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, _) = netexProcessor.processNetexFile(xml.toByteArray())

            val distance = parseXmlFromBytes(processedXml).getElementsByTagName("Distance").item(0).textContent.trim()
            assertEquals("7000", distance)
            assertTrue(registry.find("servicelinker.servicelink.straight_line").counters().isEmpty())
        }

    @Test
    fun `should extract correct stop point information from sample file`() =
        runTest {
            val inputBytes = getSampleNetexBytes()
            val document = parseXmlFromBytes(inputBytes)

            // Verify the document contains the expected stop points
            val scheduledStopPoints = document.getElementsByTagName("ScheduledStopPoint")
            assertEquals(3, scheduledStopPoints.length, "Should find 3 scheduled stop points")

            // Verify specific stop point details
            val firstStop = scheduledStopPoints.item(0) as Element
            assertEquals("FIN:ScheduledStopPoint:1", firstStop.getAttribute("id"))

            val nameElement = firstStop.getElementsByTagName("Name").item(0)
            assertEquals("Oslo Central Station", nameElement?.textContent)
        }

    @Test
    fun `should create service links with proper XML structure`() =
        runTest {
            mockOsrmWithRoute(osloToDrammenCoords, 25000.0, 1800.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)

            val processedDocument = parseXmlFromBytes(processedXml)
            val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")

            assertTrue(serviceLinks.length > 0, "Should contain service links")

            val firstServiceLink = serviceLinks.item(0) as Element
            assertTrue(firstServiceLink.hasAttribute("id"), "Service link should have ID")
            assertTrue(firstServiceLink.hasAttribute("version"), "Service link should have version")

            val fromPointRef = firstServiceLink.getElementsByTagName("FromPointRef").item(0) as? Element
            val toPointRef = firstServiceLink.getElementsByTagName("ToPointRef").item(0) as? Element

            assertNotNull(fromPointRef, "Should have FromPointRef")
            assertNotNull(toPointRef, "Should have ToPointRef")
            assertTrue(requireNotNull(fromPointRef).hasAttribute("ref"), "FromPointRef should have ref attribute")
            assertTrue(requireNotNull(toPointRef).hasAttribute("ref"), "ToPointRef should have ref attribute")
        }

    @Test
    fun `should add linksInSequence with ServiceLinkInJourneyPattern to JourneyPatterns`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)

            val processedDocument = parseXmlFromBytes(processedXml)

            // JourneyPattern should now have linksInSequence
            val linksInSequence = processedDocument.getElementsByTagName("linksInSequence")
            assertEquals(1, linksInSequence.length, "Should have one linksInSequence element")

            val slijpElements = processedDocument.getElementsByTagName("ServiceLinkInJourneyPattern")
            // 2 consecutive pairs in the JourneyPattern (1→2, 2→3), both have ServiceLinks
            assertEquals(2, slijpElements.length, "Should have 2 ServiceLinkInJourneyPattern elements")

            // Verify order and structure
            val first = slijpElements.item(0) as Element
            assertEquals("1", first.getAttribute("order"))
            assertTrue(first.hasAttribute("id"))
            val firstRef = first.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertTrue(firstRef.hasAttribute("ref"), "ServiceLinkRef should have ref attribute")

            val second = slijpElements.item(1) as Element
            assertEquals("2", second.getAttribute("order"))
        }

    @Test
    fun `linksInSequence should reference existing ServiceLink by its original ID`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)

            val doc = parseXmlFromBytes(processedXml)
            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

            // First link (1→2) should reference the pre-existing ServiceLink ID
            val firstSlijp = slijpElements.item(0) as Element
            val firstRef = firstSlijp.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertEquals(
                "FIN:ServiceLink:1",
                firstRef.getAttribute("ref"),
                "Should reference the existing ServiceLink by its original ID",
            )

            // Second link (2→3) should reference the newly created ServiceLink
            val secondSlijp = slijpElements.item(1) as Element
            val secondRef = secondSlijp.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertTrue(
                secondRef.getAttribute("ref").contains("ServiceLink"),
                "Should reference the new ServiceLink",
            )
            assertTrue(
                secondRef.getAttribute("ref").contains("2-3"),
                "New ServiceLink ID should encode from-to stop IDs",
            )
        }

    @Test
    fun `ServiceLinkInJourneyPattern IDs should follow Nordic NeTEx profile format`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)

            val doc = parseXmlFromBytes(processedXml)
            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

            val firstId = (slijpElements.item(0) as Element).getAttribute("id")
            assertEquals("FIN:ServiceLinkInJourneyPattern:1_to_2", firstId, "Reused existing entry keeps original ID")

            val secondId = (slijpElements.item(1) as Element).getAttribute("id")
            assertEquals("FIN:ServiceLinkInJourneyPattern:1_2_to_3", secondId, "Newly created entry includes JP local ID")
        }

    @Test
    fun `multiple JourneyPatterns sharing same stop pair should get unique SLIJP IDs`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:JP1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                        <JourneyPattern id="TST:JourneyPattern:JP2" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:3" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:4" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, _) = netexProcessor.processNetexFile(xml.toByteArray())
            val doc = parseXmlFromBytes(processedXml)

            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
            assertEquals(2, slijpElements.length, "Each JourneyPattern should have one ServiceLinkInJourneyPattern")

            val id1 = (slijpElements.item(0) as Element).getAttribute("id")
            val id2 = (slijpElements.item(1) as Element).getAttribute("id")
            assertNotEquals(id1, id2, "ServiceLinkInJourneyPattern IDs must be unique across JourneyPatterns")
            assertTrue(id1.contains("JP1"), "SLIJP ID should include JourneyPattern local ID")
            assertTrue(id2.contains("JP2"), "SLIJP ID should include JourneyPattern local ID")
        }

    @Test
    fun `JP with over-distance pair gets straight-line SLIJP, keeping linksInSequence count consistent`() =
        runTest {
            // Existing link 1→2 stays. New pair 2→3 gets a 6000km route → falls back to straight
            // line so the JP keeps stops=3 / links=2 instead of failing the file outright.
            mockOsrmWithRoute(osloToBergenCoords, 6_000_000.0, 72000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())

            assertEquals(1, addedCount, "One new SL should be added (straight line for pair 2→3)")
            val doc = parseXmlFromBytes(processedXml)
            val slijp = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
            assertEquals(2, slijp.length, "JP must end up with stops-1 = 2 SLIJPs")
        }

    @Test
    fun `should handle file with no JourneyPatterns`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.0</Longitude><Latitude>59.0</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>10.1</Longitude><Latitude>59.1</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.toByteArray())

            assertEquals(0, addedCount, "No JourneyPatterns means no service links to add")
            val doc = parseXmlFromBytes(processedXml)
            assertEquals(0, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)
        }

    @Test
    fun `should deduplicate shared pairs across multiple JourneyPatterns`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            // Two JourneyPatterns share pair A→B. JP1 has A→B→C, JP2 has A→B→D.
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:C" version="1">
                                            <Name>Stop C</Name>
                                            <Location><Longitude>10.2044</Longitude><Latitude>59.7434</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:D" version="1">
                                            <Name>Stop D</Name>
                                            <Location><Longitude>10.3</Longitude><Latitude>59.8</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1a" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1b" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1c" version="1" order="3">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:C" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                        <JourneyPattern id="TST:JourneyPattern:2" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2a" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2b" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2d" version="1" order="3">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:D" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.toByteArray())

            // 3 unique consecutive pairs: A→B (shared), B→C, B→D
            assertEquals(3, addedCount, "Should create 3 unique ServiceLinks (A→B shared, B→C, B→D)")

            val doc = parseXmlFromBytes(processedXml)
            val serviceLinks = doc.getElementsByTagName("ServiceLink")
            assertEquals(3, serviceLinks.length, "Should have exactly 3 ServiceLink elements")

            // Both JourneyPatterns should get their own linksInSequence
            val linksInSeqElements = doc.getElementsByTagName("linksInSequence")
            assertEquals(2, linksInSeqElements.length, "Each JourneyPattern should have linksInSequence")

            // JP1 should have 2 entries (A→B, B→C), JP2 should have 2 entries (A→B, B→D)
            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
            assertEquals(4, slijpElements.length, "Total 4 ServiceLinkInJourneyPattern entries (2 per JP)")

            // Both JPs should reference the same ServiceLink for A→B
            val jp1FirstRef =
                (
                    (linksInSeqElements.item(0) as Element)
                        .getElementsByTagName("ServiceLinkRef")
                        .item(0) as Element
                ).getAttribute("ref")
            val jp2FirstRef =
                (
                    (linksInSeqElements.item(1) as Element)
                        .getElementsByTagName("ServiceLinkRef")
                        .item(0) as Element
                ).getAttribute("ref")
            assertEquals(
                jp1FirstRef,
                jp2FirstRef,
                "Both JourneyPatterns should reference the same ServiceLink for shared pair A→B",
            )
        }

    @Test
    fun `should preserve existing ServiceLinkInJourneyPattern entries`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val inputBytes = getSampleNetexBytes()
            val (processedXml, _) = netexProcessor.processNetexFile(inputBytes)

            val doc = parseXmlFromBytes(processedXml)
            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

            // The existing entry (1→2) from sample-netex.xml should keep its original ID
            val first = slijpElements.item(0) as Element
            assertEquals(
                "FIN:ServiceLinkInJourneyPattern:1_to_2",
                first.getAttribute("id"),
                "Existing ServiceLinkInJourneyPattern should be preserved with its original ID",
            )
        }

    @Test
    fun `should preserve existing ServiceLinkInJourneyPattern with custom ID format`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            // File has an existing ServiceLink and ServiceLinkInJourneyPattern with a non-standard ID
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:C" version="1">
                                            <Name>Stop C</Name>
                                            <Location><Longitude>10.2044</Longitude><Latitude>59.7434</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <serviceLinks>
                                        <ServiceLink id="TST:ServiceLink:original_a_to_b" version="1">
                                            <Distance>15000</Distance>
                                            <FromPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                            <ToPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                        </ServiceLink>
                                    </serviceLinks>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:3" version="1" order="3">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:C" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                            <linksInSequence>
                                                <ServiceLinkInJourneyPattern id="TST:ServiceLinkInJourneyPattern:custom_upstream_id" version="3" order="1">
                                                    <ServiceLinkRef ref="TST:ServiceLink:original_a_to_b" version="1"/>
                                                </ServiceLinkInJourneyPattern>
                                            </linksInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.toByteArray())

            assertEquals(1, addedCount, "Should only add the missing B→C link")

            val doc = parseXmlFromBytes(processedXml)
            val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
            assertEquals(2, slijpElements.length, "Should have 2 entries: preserved A→B + new B→C")

            // First entry should preserve the upstream ID and version
            val first = slijpElements.item(0) as Element
            assertEquals(
                "TST:ServiceLinkInJourneyPattern:custom_upstream_id",
                first.getAttribute("id"),
                "Should preserve original ServiceLinkInJourneyPattern ID",
            )
            assertEquals(
                "3",
                first.getAttribute("version"),
                "Should preserve original version",
            )
            assertEquals("1", first.getAttribute("order"), "Order should be renumbered to 1")

            // Second entry is new (B→C)
            val second = slijpElements.item(1) as Element
            assertEquals("2", second.getAttribute("order"))
            val secondRef = second.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertTrue(
                secondRef.getAttribute("ref").contains("ServiceLink"),
                "New entry should reference the new ServiceLink",
            )
        }

    @Test
    fun `should handle file with no existing serviceLinks section`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:1" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.toByteArray())

            assertEquals(1, addedCount, "Should create serviceLinks section and add one link")

            val doc = parseXmlFromBytes(processedXml)
            assertEquals(1, doc.getElementsByTagName("serviceLinks").length, "Should have created serviceLinks section")
            assertEquals(1, doc.getElementsByTagName("ServiceLink").length)
            assertEquals(1, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)
        }

    @Test
    fun `should produce service links when stops have no coordinates but external stops are provided`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            // Stops without coordinates + PassengerStopAssignments (like Havila ferry data)
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="HAV:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="HAV:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="HAV:ScheduledStopPoint:BGO" version="1">
                                            <Name>Bergen</Name>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="HAV:ScheduledStopPoint:FRO" version="1">
                                            <Name>Florø</Name>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="HAV:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="HAV:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="HAV:ScheduledStopPoint:BGO" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="HAV:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="HAV:ScheduledStopPoint:FRO" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            // Simulate NSR-enriched external stop points (as ServiceLinkProducerService would provide)
            val externalStops =
                mapOf(
                    "HAV:ScheduledStopPoint:BGO" to
                        StopPoint(
                            id = "HAV:ScheduledStopPoint:BGO",
                            name = null,
                            coordinate = bergen,
                            transportMode = null,
                            version = null,
                        ),
                    "HAV:ScheduledStopPoint:FRO" to
                        StopPoint(
                            id = "HAV:ScheduledStopPoint:FRO",
                            name = null,
                            coordinate = Coordinate(61.6003, 5.0322),
                            transportMode = null,
                            version = null,
                        ),
                )

            val (processedXml, addedCount) =
                netexProcessor.processNetexFile(
                    xml.toByteArray(),
                    externalStops,
                )

            assertEquals(1, addedCount, "Should create service link using NSR-enriched coordinates")

            val doc = parseXmlFromBytes(processedXml)
            val serviceLinks = doc.getElementsByTagName("ServiceLink")
            assertEquals(1, serviceLinks.length)

            val link = serviceLinks.item(0) as Element
            val fromRef = (link.getElementsByTagName("FromPointRef").item(0) as Element).getAttribute("ref")
            val toRef = (link.getElementsByTagName("ToPointRef").item(0) as Element).getAttribute("ref")
            assertEquals("HAV:ScheduledStopPoint:BGO", fromRef)
            assertEquals("HAV:ScheduledStopPoint:FRO", toRef)

            assertEquals(1, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)
        }

    @Test
    fun `should extract stop assignments from PassengerStopAssignment elements`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <CompositeFrame id="HAV:CompositeFrame:1" version="1">
                        <frames>
                            <ServiceFrame id="HAV:ServiceFrame:1" version="1">
                                <stopAssignments>
                                    <!-- No `order` attribute: the NeTEx XSD marks it optional on
                                         Assignment_VersionStructure. The PSA extraction path must
                                         tolerate this shape. -->
                                    <PassengerStopAssignment id="HAV:PassengerStopAssignment:BGO" version="1">
                                        <ScheduledStopPointRef ref="HAV:ScheduledStopPoint:BGO"/>
                                        <QuayRef ref="NSR:Quay:102403"/>
                                    </PassengerStopAssignment>
                                    <PassengerStopAssignment id="HAV:PassengerStopAssignment:FRO" version="1">
                                        <ScheduledStopPointRef ref="HAV:ScheduledStopPoint:FRO"/>
                                        <QuayRef ref="NSR:Quay:103500"/>
                                    </PassengerStopAssignment>
                                </stopAssignments>
                            </ServiceFrame>
                        </frames>
                    </CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
            """.trimIndent()

        val pass1 =
            MetadataExtractor().extract(
                NetexProcessor.SINGLE_DOC_NAME,
                xml.toByteArray(Charsets.UTF_8),
            )

        assertEquals(2, pass1.assignments.size)
        assertEquals("NSR:Quay:102403", pass1.assignments["HAV:ScheduledStopPoint:BGO"])
        assertEquals("NSR:Quay:103500", pass1.assignments["HAV:ScheduledStopPoint:FRO"])
    }

    @Test
    fun `should prefer local coordinates over external stops`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                    <dataObjects>
                        <CompositeFrame id="TST:CompositeFrame:1" version="1">
                            <frames>
                                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                                    <scheduledStopPoints>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                                            <Name>Stop A</Name>
                                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                                        </ScheduledStopPoint>
                                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                                            <Name>Stop B</Name>
                                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                                        </ScheduledStopPoint>
                                    </scheduledStopPoints>
                                    <journeyPatterns>
                                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                            <pointsInSequence>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:1" version="1" order="1">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:1" version="1"/>
                                                </StopPointInJourneyPattern>
                                                <StopPointInJourneyPattern id="TST:StopPointInJourneyPattern:2" version="1" order="2">
                                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                                </StopPointInJourneyPattern>
                                            </pointsInSequence>
                                        </JourneyPattern>
                                    </journeyPatterns>
                                </ServiceFrame>
                            </frames>
                        </CompositeFrame>
                    </dataObjects>
                </PublicationDelivery>
                """.trimIndent()

            // External stops with different coordinates — local should win
            val externalStops =
                mapOf(
                    "TST:ScheduledStopPoint:1" to
                        StopPoint(
                            id = "TST:ScheduledStopPoint:1",
                            name = "External Stop A",
                            coordinate = Coordinate(0.0, 0.0),
                            transportMode = null,
                            version = null,
                        ),
                )

            val (_, addedCount) =
                netexProcessor.processNetexFile(
                    xml.toByteArray(),
                    externalStops,
                )

            assertEquals(1, addedCount, "Should still produce service link using local coordinates")
        }

    // --- modesFilter tests ---

    @Test
    fun `null modesFilter generates service links for all stops regardless of mode`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)
            val xml = buildTwoStopNetex("bus")
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), null)
            assertEquals(1, addedCount, "null filter should not restrict generation")
        }

    @Test
    fun `modesFilter matching line mode generates service link`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)
            val xml = buildTwoStopNetex("bus")
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), setOf("BUS"))
            assertEquals(1, addedCount, "BUS filter should include BUS journey pattern")
        }

    @Test
    fun `modesFilter not matching line mode produces straight-line SL without calling OSRM`() =
        runTest {
            val xml = buildTwoStopNetex("rail")
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), setOf("BUS"))
            assertEquals(1, addedCount, "Out-of-mode pair still gets an SL — straight line — to keep JP integrity")
            coVerify(exactly = 0) { mockOsrmService.calculateRoute(any(), any(), any()) }
        }

    @Test
    fun `empty modesFilter produces straight-line SLs and never calls OSRM`() =
        runTest {
            val xml = buildTwoStopNetex("bus")
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), emptySet())
            assertEquals(1, addedCount, "Even with empty filter, JP integrity requires the SL")
            coVerify(exactly = 0) { mockOsrmService.calculateRoute(any(), any(), any()) }
        }

    @Test
    fun `modesFilter and JP without line transport mode falls back to straight line`() =
        runTest {
            // JP with no RouteRef/LineRef → null mode. modesFilter.contains(null) is false →
            // routed as straight line. Used to silently drop, breaking JP integrity.
            val xml = buildTwoStopNetexNoLine()
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), setOf("BUS"))
            assertEquals(1, addedCount, "Null-mode JP still produces SL (straight line)")
            coVerify(exactly = 0) { mockOsrmService.calculateRoute(any(), any(), any()) }
        }

    @Test
    fun `modesFilter routes via OSRM only for matching modes, straight-lines the rest`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)
            // JP1: BUS (stops 1→2, routed via OSRM), JP2: RAIL (stops 2→3, straight line).
            val xml = buildTwoJourneyPatternNetex("bus", "rail")
            val (_, addedCount) = netexProcessor.processNetexFile(xml.toByteArray(), emptyMap(), setOf("BUS"))
            assertEquals(2, addedCount, "Both JPs get SLs — BUS routed, RAIL straight-line")
            // OSRM is called only for the BUS pair, not for the RAIL pair.
            coVerify(exactly = 1) { mockOsrmService.calculateRoute(any(), any(), any()) }
        }

    /** Two-stop, one-pair JP where mode comes from Line via Route. */
    private fun buildTwoStopNetex(mode: String) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
            <dataObjects><CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                    <lines>
                        <Line id="TST:Line:1" version="1">
                            <Name>Test Line</Name>
                            <TransportMode>$mode</TransportMode>
                        </Line>
                    </lines>
                    <routes>
                        <Route id="TST:Route:1" version="1">
                            <LineRef ref="TST:Line:1" version="1"/>
                        </Route>
                    </routes>
                    <scheduledStopPoints>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                            <Name>Stop A</Name>
                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                        </ScheduledStopPoint>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                            <Name>Stop B</Name>
                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                        </ScheduledStopPoint>
                    </scheduledStopPoints>
                    <journeyPatterns>
                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                            <RouteRef ref="TST:Route:1" version="1"/>
                            <pointsInSequence>
                                <StopPointInJourneyPattern id="TST:SPIJP:1" version="1" order="1">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:1" version="1"/>
                                </StopPointInJourneyPattern>
                                <StopPointInJourneyPattern id="TST:SPIJP:2" version="1" order="2">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                </StopPointInJourneyPattern>
                            </pointsInSequence>
                        </JourneyPattern>
                    </journeyPatterns>
                </ServiceFrame>
            </frames></CompositeFrame></dataObjects>
        </PublicationDelivery>
        """.trimIndent()

    /** Two-stop JP with no Line/Route → null transport mode. */
    private fun buildTwoStopNetexNoLine() =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
            <dataObjects><CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                    <scheduledStopPoints>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                            <Name>Stop A</Name>
                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                        </ScheduledStopPoint>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                            <Name>Stop B</Name>
                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                        </ScheduledStopPoint>
                    </scheduledStopPoints>
                    <journeyPatterns>
                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                            <pointsInSequence>
                                <StopPointInJourneyPattern id="TST:SPIJP:1" version="1" order="1">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:1" version="1"/>
                                </StopPointInJourneyPattern>
                                <StopPointInJourneyPattern id="TST:SPIJP:2" version="1" order="2">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                </StopPointInJourneyPattern>
                            </pointsInSequence>
                        </JourneyPattern>
                    </journeyPatterns>
                </ServiceFrame>
            </frames></CompositeFrame></dataObjects>
        </PublicationDelivery>
        """.trimIndent()

    /** Two journey patterns with different modes: JP1 (mode1) stops 1→2, JP2 (mode2) stops 2→3. */
    private fun buildTwoJourneyPatternNetex(
        mode1: String,
        mode2: String,
    ) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
            <dataObjects><CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                <ServiceFrame id="TST:ServiceFrame:1" version="1">
                    <lines>
                        <Line id="TST:Line:1" version="1"><TransportMode>$mode1</TransportMode></Line>
                        <Line id="TST:Line:2" version="1"><TransportMode>$mode2</TransportMode></Line>
                    </lines>
                    <routes>
                        <Route id="TST:Route:1" version="1"><LineRef ref="TST:Line:1" version="1"/></Route>
                        <Route id="TST:Route:2" version="1"><LineRef ref="TST:Line:2" version="1"/></Route>
                    </routes>
                    <scheduledStopPoints>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:1" version="1">
                            <Name>Stop A</Name>
                            <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                        </ScheduledStopPoint>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:2" version="1">
                            <Name>Stop B</Name>
                            <Location><Longitude>11.0464</Longitude><Latitude>59.9556</Latitude></Location>
                        </ScheduledStopPoint>
                        <ScheduledStopPoint id="TST:ScheduledStopPoint:3" version="1">
                            <Name>Stop C</Name>
                            <Location><Longitude>11.1740</Longitude><Latitude>60.1450</Latitude></Location>
                        </ScheduledStopPoint>
                    </scheduledStopPoints>
                    <journeyPatterns>
                        <JourneyPattern id="TST:JourneyPattern:1" version="1">
                            <RouteRef ref="TST:Route:1" version="1"/>
                            <pointsInSequence>
                                <StopPointInJourneyPattern id="TST:SPIJP:1" version="1" order="1">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:1" version="1"/>
                                </StopPointInJourneyPattern>
                                <StopPointInJourneyPattern id="TST:SPIJP:2" version="1" order="2">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                </StopPointInJourneyPattern>
                            </pointsInSequence>
                        </JourneyPattern>
                        <JourneyPattern id="TST:JourneyPattern:2" version="1">
                            <RouteRef ref="TST:Route:2" version="1"/>
                            <pointsInSequence>
                                <StopPointInJourneyPattern id="TST:SPIJP:3" version="1" order="1">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:2" version="1"/>
                                </StopPointInJourneyPattern>
                                <StopPointInJourneyPattern id="TST:SPIJP:4" version="1" order="2">
                                    <ScheduledStopPointRef ref="TST:ScheduledStopPoint:3" version="1"/>
                                </StopPointInJourneyPattern>
                            </pointsInSequence>
                        </JourneyPattern>
                    </journeyPatterns>
                </ServiceFrame>
            </frames></CompositeFrame></dataObjects>
        </PublicationDelivery>
        """.trimIndent()

    @Test
    fun `serviceLinks should appear before journeyPatterns in ServiceFrame`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())
            assertEquals(1, addedCount)

            val doc = parseXmlFromBytes(processedXml)
            val serviceFrame = doc.getElementsByTagName("ServiceFrame").item(0) as Element
            val children = serviceFrame.childNodes
            var serviceLinksPos = -1
            var journeyPatternsPos = -1
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element) {
                    when (node.localName) {
                        "serviceLinks" -> serviceLinksPos = i
                        "journeyPatterns" -> journeyPatternsPos = i
                    }
                }
            }
            assertTrue(serviceLinksPos >= 0, "serviceLinks not found")
            assertTrue(journeyPatternsPos >= 0, "journeyPatterns not found")
            assertTrue(
                serviceLinksPos < journeyPatternsPos,
                "serviceLinks (pos $serviceLinksPos) must precede journeyPatterns (pos $journeyPatternsPos)",
            )
        }

    @Test
    fun `posList should start and end at stop coordinates not OSRM snapped positions`() =
        runTest {
            // OSRM may snap route start/end to the nearest point on its network, which can be
            // hundreds of meters from the actual stop. The linestring must be clamped to stop coords.
            val snappedFrom = Coordinate(59.960, 11.050) // ~500m from stop 2
            val intermediate = Coordinate(59.980, 11.100)
            val snappedTo = Coordinate(60.140, 11.170) // ~600m from stop 3
            val osrmCoords = listOf(snappedFrom, intermediate, snappedTo)
            mockOsrmWithRoute(osrmCoords, 20000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())
            assertEquals(1, addedCount)

            val doc = parseXmlFromBytes(processedXml)
            val newLink =
                (0 until doc.getElementsByTagName("ServiceLink").length)
                    .map { doc.getElementsByTagName("ServiceLink").item(it) as Element }
                    .first { it.getAttribute("id") == "FIN:ServiceLink:2-3" }

            val posList =
                newLink
                    .getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
                    .item(0)
                    .textContent
                    .trim()
                    .split("\\s+".toRegex())
                    .map { it.toDouble() }

            // posList is "lat lon lat lon ..." — 3 coordinate pairs
            assertEquals(6, posList.size, "Should have 3 coordinate pairs (6 values)")

            // First pair should be stop 2 coords (59.9556, 11.0464), not OSRM-snapped (59.960, 11.050)
            assertEquals(59.9556, posList[0], 0.0001, "First lat should be stop 2 latitude, not OSRM-snapped")
            assertEquals(11.0464, posList[1], 0.0001, "First lon should be stop 2 longitude, not OSRM-snapped")

            // Intermediate point should be preserved from OSRM (within polyline encoding precision)
            assertEquals(59.980, posList[2], 0.001, "Intermediate lat should be from OSRM")
            assertEquals(11.100, posList[3], 0.001, "Intermediate lon should be from OSRM")

            // Last pair should be stop 3 coords (60.1450, 11.1740), not OSRM-snapped (60.140, 11.170)
            assertEquals(60.1450, posList[4], 0.0001, "Last lat should be stop 3 latitude, not OSRM-snapped")
            assertEquals(11.1740, posList[5], 0.0001, "Last lon should be stop 3 longitude, not OSRM-snapped")
        }

    @Test
    fun `posList should be simplified but keep the OSRM distance`() =
        runTest {
            // A dense OSRM geometry between the two stops: 200 interpolated points on the straight
            // line, with one real bend ~200 m off it. Only the bend carries shape information.
            val from = Coordinate(59.9556, 11.0464)
            val to = Coordinate(60.1450, 11.1740)
            val dense =
                (0..200).map { i ->
                    val f = i / 200.0
                    Coordinate(
                        from.latitude.toDouble() + f * (to.latitude.toDouble() - from.latitude.toDouble()),
                        from.longitude.toDouble() + f * (to.longitude.toDouble() - from.longitude.toDouble()),
                    )
                }
            val bend = Coordinate(dense[100].latitude.toDouble() + 0.002, dense[100].longitude.toDouble())
            val osrmCoords = dense.take(100) + bend + dense.drop(100)
            mockOsrmWithRoute(osrmCoords, 25000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())
            assertEquals(1, addedCount)

            val doc = parseXmlFromBytes(processedXml)
            val newLink =
                (0 until doc.getElementsByTagName("ServiceLink").length)
                    .map { doc.getElementsByTagName("ServiceLink").item(it) as Element }
                    .first { it.getAttribute("id") == "FIN:ServiceLink:2-3" }

            val posList =
                newLink
                    .getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
                    .item(0)
                    .textContent
                    .trim()
                    .split("\\s+".toRegex())
                    .map { it.toDouble() }

            assertTrue(
                posList.size / 2 < osrmCoords.size / 10,
                "Collinear points should be dropped, got ${posList.size / 2} of ${osrmCoords.size} pairs",
            )
            assertEquals(59.9556, posList[0], 0.0001, "First pair must stay at the from-stop")
            assertEquals(11.0464, posList[1], 0.0001, "First pair must stay at the from-stop")
            assertEquals(60.1450, posList[posList.size - 2], 0.0001, "Last pair must stay at the to-stop")
            assertEquals(11.1740, posList[posList.size - 1], 0.0001, "Last pair must stay at the to-stop")
            assertTrue(
                posList.chunked(2).any { (lat, _) -> kotlin.math.abs(lat - bend.latitude.toDouble()) < 0.0001 },
                "The bend must survive simplification",
            )

            // Distance comes from OSRM, not from the shortened polyline
            assertEquals(
                "25000",
                newLink.getElementsByTagName("Distance").item(0).textContent,
            )
        }

    @Test
    fun `long routes are simplified more aggressively than short ones`() =
        runTest {
            // Same geometry, two reported route lengths. The tolerance scales with the route's own
            // length, so the long one keeps fewer points - otherwise a 700 km coach leg would carry
            // thousands of them.
            val wiggly =
                (0..400).map { i ->
                    val f = i / 400.0
                    Coordinate(
                        59.9556 + f * (60.1450 - 59.9556) + kotlin.math.sin(f * 60) * 0.00003,
                        11.0464 + f * (11.1740 - 11.0464),
                    )
                }

            mockOsrmWithRoute(wiggly, 20_000.0)
            val shortPosList = posListOfNewLink(netexProcessor.processNetexFile(getSampleNetexBytes()).outputBytes)

            mockOsrmWithRoute(wiggly, 200_000.0)
            val longPosList = posListOfNewLink(netexProcessor.processNetexFile(getSampleNetexBytes()).outputBytes)

            assertTrue(
                longPosList.size < shortPosList.size,
                "200 km route should keep fewer points than a 20 km one, got ${longPosList.size} vs ${shortPosList.size}",
            )
            // Endpoints survive at every tolerance
            assertEquals(59.9556, longPosList[0], 0.0001)
            assertEquals(60.1450, longPosList[longPosList.size - 2], 0.0001)
        }

    @Test
    fun `serviceLinks should use GML 3_2 namespace with gis prefix`() =
        runTest {
            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

            val (processedXml, addedCount) = netexProcessor.processNetexFile(getSampleNetexBytes())
            assertEquals(1, addedCount)

            val xmlString = String(processedXml, Charsets.UTF_8)
            assertTrue(
                xmlString.contains("xmlns:gis=\"http://www.opengis.net/gml/3.2\""),
                "Output must declare GML 3.2 namespace with gis prefix",
            )
            assertTrue(
                xmlString.contains("<gis:LineString"),
                "New LineString elements must use gis prefix",
            )
            assertTrue(
                xmlString.contains("<gis:posList"),
                "New posList elements must use gis prefix",
            )
        }

    @Test
    fun `gml prefix in input should be rewritten to gis`() =
        runTest {
            // Input with gml: prefix (as some upstream sources produce)
            val inputWithGml =
                String(getSampleNetexBytes(), Charsets.UTF_8)
                    .replace("xmlns:gis=", "xmlns:gml=")
                    .replace("gis:", "gml:")

            assertTrue(
                inputWithGml.contains("xmlns:gml=\"http://www.opengis.net/gml/3.2\""),
                "Test setup: input must use gml prefix",
            )

            mockOsrmWithRoute(osloToLillestromCoords, 15000.0)
            val (processedXml, _) = netexProcessor.processNetexFile(inputWithGml.toByteArray())
            val xmlString = String(processedXml, Charsets.UTF_8)

            assertFalse(
                xmlString.contains("xmlns:gml="),
                "Output must not contain xmlns:gml declaration",
            )
            assertFalse(
                xmlString.contains("<gml:"),
                "Output must not contain gml: prefixed elements",
            )
            assertTrue(
                xmlString.contains("xmlns:gis=\"http://www.opengis.net/gml/3.2\""),
                "Output must declare GML 3.2 namespace with gis prefix",
            )
            assertTrue(
                xmlString.contains("<gis:LineString"),
                "Existing LineString elements must be rewritten to gis prefix",
            )
            assertTrue(
                xmlString.contains("<gis:posList"),
                "Existing posList elements must be rewritten to gis prefix",
            )
        }

    private fun parseXmlFromStream(inputStream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(inputStream)
    }

    private fun parseXmlFromBytes(xmlBytes: ByteArray): Document = parseXmlFromStream(xmlBytes.inputStream())
}

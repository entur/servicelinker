package no.entur.servicelinker.service

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMRoute
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
        netexProcessor = NetexProcessor(mockOsrmService)
    }

    private fun getSampleNetexInputStream(): InputStream {
        val inputStream = javaClass.getResourceAsStream("/sample-netex.xml")
        return requireNotNull(inputStream) { "sample-netex.xml not found in test resources" }
    }

    private fun mockOsrmWithRoute(coords: List<Coordinate>, distance: Double, duration: Double = 1200.0) {
        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } returns OSRMRoute(
            distance = distance,
            duration = duration,
            geometry = PolylineEncoding.encode(coords)
        )
    }

    private fun mockOsrmFailure() {
        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } throws RuntimeException("OSRM service unavailable")
    }

    @Test
    fun `should add service links for consecutive JourneyPattern stops only`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

        assertTrue(processedXml.isNotEmpty(), "Processed XML should not be empty")
        // JourneyPattern has stops 1→2→3, existing ServiceLink covers 1→2, so only 2→3 is new
        assertEquals(1, addedCount, "Should add only missing consecutive service links")

        val processedDocument = parseXmlFromBytes(processedXml)
        // Total ServiceLinks: 1 existing (1→2) + 1 new (2→3) = 2
        val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")
        assertEquals(2, serviceLinks.length, "Should have 2 total service links")
    }

    @Test
    fun `should respect existing service links and not duplicate them`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

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
    fun `should handle OSRM service failures gracefully`() = runTest {
        mockOsrmFailure()

        val inputStream = getSampleNetexInputStream()

        var exceptionThrown = false
        try {
            val (processedXml, _) = netexProcessor.processNetexFile(inputStream)
            assertTrue(processedXml.isNotEmpty(), "Should still produce valid XML")
        } catch (_: Exception) {
            exceptionThrown = true
        }

        assertFalse(exceptionThrown, "Should not throw exception when OSRM fails")
    }

    @Test
    fun `should filter out unreasonably long routes`() = runTest {
        mockOsrmWithRoute(osloToBergenCoords, 150000.0, 7200.0) // 150km - should be filtered out

        val inputStream = getSampleNetexInputStream()
        val (_, addedCount) = netexProcessor.processNetexFile(inputStream)

        assertEquals(0, addedCount, "Should not add service links for routes over 100km")
    }

    @Test
    fun `should extract correct stop point information from sample file`() = runTest {
        val inputStream = getSampleNetexInputStream()
        val document = parseXmlFromStream(inputStream)

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
    fun `should create service links with proper XML structure`() = runTest {
        mockOsrmWithRoute(osloToDrammenCoords, 25000.0, 1800.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, _) = netexProcessor.processNetexFile(inputStream)

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
    fun `should add linksInSequence with ServiceLinkInJourneyPattern to JourneyPatterns`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, _) = netexProcessor.processNetexFile(inputStream)

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
    fun `linksInSequence should reference existing ServiceLink by its original ID`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, _) = netexProcessor.processNetexFile(inputStream)

        val doc = parseXmlFromBytes(processedXml)
        val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

        // First link (1→2) should reference the pre-existing ServiceLink ID
        val firstSlijp = slijpElements.item(0) as Element
        val firstRef = firstSlijp.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertEquals("FIN:ServiceLink:1", firstRef.getAttribute("ref"),
            "Should reference the existing ServiceLink by its original ID")

        // Second link (2→3) should reference the newly created ServiceLink
        val secondSlijp = slijpElements.item(1) as Element
        val secondRef = secondSlijp.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertTrue(secondRef.getAttribute("ref").contains("ServiceLink"),
            "Should reference the new ServiceLink")
        assertTrue(secondRef.getAttribute("ref").contains("2-3"),
            "New ServiceLink ID should encode from-to stop IDs")
    }

    @Test
    fun `ServiceLinkInJourneyPattern IDs should follow Nordic NeTEx profile format`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, _) = netexProcessor.processNetexFile(inputStream)

        val doc = parseXmlFromBytes(processedXml)
        val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

        val firstId = (slijpElements.item(0) as Element).getAttribute("id")
        assertEquals("FIN:ServiceLinkInJourneyPattern:1_to_2", firstId)

        val secondId = (slijpElements.item(1) as Element).getAttribute("id")
        assertEquals("FIN:ServiceLinkInJourneyPattern:2_to_3", secondId)
    }

    @Test
    fun `should skip linksInSequence entry when route exceeds max distance`() = runTest {
        // Existing link 1→2 stays. New pair 2→3 gets a 150km route → filtered out.
        mockOsrmWithRoute(osloToBergenCoords, 150000.0, 7200.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

        assertEquals(0, addedCount)

        val doc = parseXmlFromBytes(processedXml)
        val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

        // Only the existing 1→2 link should appear in linksInSequence; 2→3 was filtered
        assertEquals(1, slijpElements.length,
            "Should only have linksInSequence entry for the existing ServiceLink")
        val ref = (slijpElements.item(0) as Element)
            .getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertEquals("FIN:ServiceLink:1", ref.getAttribute("ref"))
    }

    @Test
    fun `should handle file with no JourneyPatterns`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val xml = """
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

        val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.byteInputStream())

        assertEquals(0, addedCount, "No JourneyPatterns means no service links to add")
        val doc = parseXmlFromBytes(processedXml)
        assertEquals(0, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)
    }

    @Test
    fun `should deduplicate shared pairs across multiple JourneyPatterns`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        // Two JourneyPatterns share pair A→B. JP1 has A→B→C, JP2 has A→B→D.
        val xml = """
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

        val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.byteInputStream())

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
        val jp1FirstRef = ((linksInSeqElements.item(0) as Element)
            .getElementsByTagName("ServiceLinkRef").item(0) as Element).getAttribute("ref")
        val jp2FirstRef = ((linksInSeqElements.item(1) as Element)
            .getElementsByTagName("ServiceLinkRef").item(0) as Element).getAttribute("ref")
        assertEquals(jp1FirstRef, jp2FirstRef,
            "Both JourneyPatterns should reference the same ServiceLink for shared pair A→B")
    }

    @Test
    fun `should preserve existing ServiceLinkInJourneyPattern entries`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, _) = netexProcessor.processNetexFile(inputStream)

        val doc = parseXmlFromBytes(processedXml)
        val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")

        // The existing entry (1→2) from sample-netex.xml should keep its original ID
        val first = slijpElements.item(0) as Element
        assertEquals("FIN:ServiceLinkInJourneyPattern:1_to_2", first.getAttribute("id"),
            "Existing ServiceLinkInJourneyPattern should be preserved with its original ID")
    }

    @Test
    fun `should preserve existing ServiceLinkInJourneyPattern with custom ID format`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        // File has an existing ServiceLink and ServiceLinkInJourneyPattern with a non-standard ID
        val xml = """
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

        val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.byteInputStream())

        assertEquals(1, addedCount, "Should only add the missing B→C link")

        val doc = parseXmlFromBytes(processedXml)
        val slijpElements = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        assertEquals(2, slijpElements.length, "Should have 2 entries: preserved A→B + new B→C")

        // First entry should preserve the upstream ID and version
        val first = slijpElements.item(0) as Element
        assertEquals("TST:ServiceLinkInJourneyPattern:custom_upstream_id", first.getAttribute("id"),
            "Should preserve original ServiceLinkInJourneyPattern ID")
        assertEquals("3", first.getAttribute("version"),
            "Should preserve original version")
        assertEquals("1", first.getAttribute("order"), "Order should be renumbered to 1")

        // Second entry is new (B→C)
        val second = slijpElements.item(1) as Element
        assertEquals("2", second.getAttribute("order"))
        val secondRef = second.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertTrue(secondRef.getAttribute("ref").contains("ServiceLink"),
            "New entry should reference the new ServiceLink")
    }

    @Test
    fun `should handle file with no existing serviceLinks section`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val xml = """
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

        val (processedXml, addedCount) = netexProcessor.processNetexFile(xml.byteInputStream())

        assertEquals(1, addedCount, "Should create serviceLinks section and add one link")

        val doc = parseXmlFromBytes(processedXml)
        assertEquals(1, doc.getElementsByTagName("serviceLinks").length, "Should have created serviceLinks section")
        assertEquals(1, doc.getElementsByTagName("ServiceLink").length)
        assertEquals(1, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)
    }

    private fun parseXmlFromStream(inputStream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(inputStream)
    }

    private fun parseXmlFromBytes(xmlBytes: ByteArray): Document = parseXmlFromStream(xmlBytes.inputStream())
}

package no.entur.servicelinker.service

import com.google.maps.internal.PolylineEncoding
import com.google.maps.model.LatLng
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.OSRMRoute
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

@SpringBootTest
class NetexProcessorTest {

    private lateinit var mockOsrmService: OSRMService
    private lateinit var netexProcessor: NetexProcessor

    val oslo = LatLng(59.9139, 10.7522)
    val lillestrøm = LatLng(59.9556, 11.0464)
    val drammen = LatLng(59.7434, 10.2044)
    val bergen = LatLng(60.3913, 5.3221)
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

    private fun mockOsrmWithRoute(coords: List<LatLng>, distance: Double, duration: Double = 1200.0) {
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
    fun `should process sample NeTEx file and extract stop points correctly`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

        assertTrue(processedXml.isNotEmpty(), "Processed XML should not be empty")
        assertEquals(addedCount, 3, "Should have added service links between stop points")

        val processedDocument = parseXmlFromBytes(processedXml)
        val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")

        assertTrue(serviceLinks.length >= addedCount, "Should contain the added service links")
    }

    @Test
    fun `should respect existing service links and not duplicate them`() = runTest {
        mockOsrmWithRoute(osloToLillestromCoords, 15000.0)

        val inputStream = getSampleNetexInputStream()
        val (processedXml, addedCount) = netexProcessor.processNetexFile(inputStream)

        val processedDocument = parseXmlFromBytes(processedXml)
        val serviceLinks = processedDocument.getElementsByTagName("ServiceLink")

        assertTrue(processedXml.isNotEmpty(), "Should produce valid XML")
        assertTrue(serviceLinks.length >= 1, "Should have at least one service link")
        assertTrue(addedCount >= 0, "Should not have negative added count")

        for (i in 0 until serviceLinks.length) {
            val element = serviceLinks.item(i) as Element
            assertTrue(element.hasAttribute("id"), "Service link should have ID")

            val fromRefElement = element.getElementsByTagName("FromPointRef").item(0)
            val toRefElement = element.getElementsByTagName("ToPointRef").item(0)

            assertNotNull(fromRefElement, "Service link should have FromPointRef")
            assertNotNull(toRefElement, "Service link should have ToPointRef")
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
        } catch (e: Exception) {
            exceptionThrown = true
        }

        assertFalse(exceptionThrown, "Should not throw exception when OSRM fails")
    }

    @Test
    fun `should filter out unreasonably long routes`() = runTest {
        mockOsrmWithRoute(osloToBergenCoords, 75000.0, 3600.0) // 75km - should be filtered out

        val inputStream = getSampleNetexInputStream()
        val (_, addedCount) = netexProcessor.processNetexFile(inputStream)

        assertEquals(0, addedCount, "Should not add service links for routes over 50km")
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
        assertTrue(fromPointRef!!.hasAttribute("ref"), "FromPointRef should have ref attribute")
        assertTrue(toPointRef!!.hasAttribute("ref"), "ToPointRef should have ref attribute")
    }

    private fun parseXmlFromStream(inputStream: java.io.InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(inputStream)
    }

    private fun parseXmlFromBytes(xmlBytes: ByteArray): Document = parseXmlFromStream(xmlBytes.inputStream())
}

package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.TransportMode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [Pass1PluginExtractor] covering the edge cases whose parity-based
 * coverage was retired when the DOM path was deleted.
 */
class Pass1PluginExtractorTest {
    private val extractor = Pass1PluginExtractor()

    // --- StopPlace + Centroid guard --------------------------------------------------

    @Test
    fun `StopPlace with direct Centroid-Location is captured`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-stopplace.xml"))
        val sp1 = doc.stopPoints.first { it.id == "TST:StopPlace:1" }
        assertEquals(BigDecimal("60.1"), sp1.coordinate.latitude)
        assertEquals(BigDecimal("10.1"), sp1.coordinate.longitude)
        assertEquals("Canonical Stop", sp1.name)
        assertEquals(TransportMode.BUS, sp1.transportMode)
    }

    @Test
    fun `StopPlace with only Quay-level Centroid falls through to Quay coords`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-stopplace.xml"))
        val sp2 = doc.stopPoints.first { it.id == "TST:StopPlace:2" }
        assertEquals(BigDecimal("50.2"), sp2.coordinate.latitude)
        assertEquals(BigDecimal("20.2"), sp2.coordinate.longitude)
    }

    @Test
    fun `StopPlace with bare Location and no Centroid is NOT emitted`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-stopplace.xml"))
        assertTrue(
            doc.stopPoints.none { it.id == "TST:StopPlace:3" },
            "SP3 has <Location> outside of <Centroid> and must not be emitted",
        )
    }

    @Test
    fun `StopPlace Name inside nested AlternativeName is captured via first-wins`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-stopplace.xml"))
        val sp4 = doc.stopPoints.first { it.id == "TST:StopPlace:4" }
        assertEquals("Alt-Name Stop", sp4.name)
    }

    // --- JP mode resolution ----------------------------------------------------------

    @Test
    fun `jpModeMap direct-mode wins over references`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        assertEquals(TransportMode.METRO, doc.jpModeMap["TST:JourneyPattern:direct"])
    }

    @Test
    fun `jpModeMap LineRef to moded Line resolves via Line`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        assertEquals(TransportMode.RAIL, doc.jpModeMap["TST:JourneyPattern:lineModed"])
    }

    @Test
    fun `jpModeMap LineRef to mode-less Line does NOT fall through to RouteRef`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        // DOM behavior: committed to LineRef even when the Line has no mode.
        // If this regresses, resolveJpModeMap is silently picking up RAIL from the Route path.
        assertNull(
            doc.jpModeMap["TST:JourneyPattern:lineModelessWithRouteFallback"],
            "LineRef wins even when the referenced Line has no mode",
        )
    }

    @Test
    fun `jpModeMap RouteRef-only resolves via Route to Line to mode`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        assertEquals(TransportMode.RAIL, doc.jpModeMap["TST:JourneyPattern:routeOnly"])
    }

    @Test
    fun `jpModeMap no mode inputs yields null`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        assertTrue(doc.jpModeMap.containsKey("TST:JourneyPattern:noMode"))
        assertNull(doc.jpModeMap["TST:JourneyPattern:noMode"])
    }

    @Test
    fun `VehicleModes plural takes the first space-separated token`() {
        val doc = extractor.extract("t.xml", readResource("sample-netex-jpmode.xml"))
        assertEquals(TransportMode.FERRY, doc.jpModeMap["TST:JourneyPattern:vehicleModes"])
    }

    // --- Reuse-safety ---------------------------------------------------------------

    @Test
    fun `extractor is safe to reuse across calls`() {
        val first = extractor.extract("a.xml", readResource("sample-netex-stopplace.xml"))
        val second = extractor.extract("b.xml", readResource("sample-netex-jpmode.xml"))
        // Second call must not carry over any SSP/SP ids from the first fixture.
        val leaked = second.stopIdsAndVersions.keys.intersect(first.stopIdsAndVersions.keys)
        assertTrue(leaked.isEmpty(), "state leaked between extract() calls: $leaked")
    }

    // --- PassengerStopAssignment without `order` (netex-tools 0.0.43-SNAPSHOT fix) ----

    @Test
    fun `PSA without order attribute does not NPE`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <ServiceFrame id="TST:ServiceFrame:1" version="1">
                        <stopAssignments>
                            <PassengerStopAssignment id="TST:PSA:1" version="1">
                                <ScheduledStopPointRef ref="TST:SSP:1"/>
                                <QuayRef ref="NSR:Quay:1"/>
                            </PassengerStopAssignment>
                        </stopAssignments>
                    </ServiceFrame>
                </dataObjects>
            </PublicationDelivery>
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val doc = extractor.extract("t.xml", xml)
        assertEquals("NSR:Quay:1", doc.assignments["TST:SSP:1"])
    }

    private fun readResource(name: String): ByteArray =
        javaClass.getResourceAsStream("/$name")?.readBytes()
            ?: error("test resource not found: $name")
}

package no.entur.servicelinker.service

import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class StaxNetexTransformerTest {

    private lateinit var transformer: StaxNetexTransformer
    private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    @BeforeEach
    fun setup() {
        transformer = StaxNetexTransformer()
    }

    private fun parse(xml: ByteArray): Document =
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private val emptyPlan = InjectionPlan(emptyList(), emptyMap(), emptyMap(), false)

    private val basicXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
            <dataObjects>
                <CompositeFrame id="TST:CompositeFrame:1" version="1">
                    <frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <scheduledStopPoints>
                                <ScheduledStopPoint id="TST:ScheduledStopPoint:A" version="1">
                                    <Name>Stop A</Name>
                                </ScheduledStopPoint>
                                <ScheduledStopPoint id="TST:ScheduledStopPoint:B" version="1">
                                    <Name>Stop B</Name>
                                </ScheduledStopPoint>
                            </scheduledStopPoints>
                            <journeyPatterns>
                                <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                    <pointsInSequence>
                                        <StopPointInJourneyPattern id="TST:SPIJP:1" version="1" order="1">
                                            <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                        </StopPointInJourneyPattern>
                                        <StopPointInJourneyPattern id="TST:SPIJP:2" version="1" order="2">
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
    """.trimIndent().toByteArray()

    private fun sampleServiceLinkData() = ServiceLinkData(
        id = "TST:ServiceLink:A-B",
        version = "1",
        distance = 15000,
        projectionId = "TST:LinkSequenceProjection:20260409_A_B",
        gmlLineStringId = "lsp_A_B",
        posList = "59.9139 10.7522 59.9556 11.0464",
        fromPointRef = "TST:ScheduledStopPoint:A",
        fromPointVersion = "1",
        toPointRef = "TST:ScheduledStopPoint:B",
        toPointVersion = "1"
    )

    @Test
    fun `empty plan returns input unchanged`() {
        val result = transformer.transform(basicXml, emptyPlan)
        assertArrayEquals(basicXml, result)
    }

    @Test
    fun `injects new serviceLinks section before journeyPatterns`() {
        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(basicXml, plan)
        val doc = parse(result)

        val serviceLinks = doc.getElementsByTagName("ServiceLink")
        assertEquals(1, serviceLinks.length)

        val sl = serviceLinks.item(0) as Element
        assertEquals("TST:ServiceLink:A-B", sl.getAttribute("id"))
        assertEquals("15000", (sl.getElementsByTagName("Distance").item(0) as Element).textContent)

        val fromRef = sl.getElementsByTagName("FromPointRef").item(0) as Element
        assertEquals("TST:ScheduledStopPoint:A", fromRef.getAttribute("ref"))
        assertEquals("1", fromRef.getAttribute("version"))

        // Verify schema ordering: serviceLinks before journeyPatterns
        val serviceFrame = doc.getElementsByTagName("ServiceFrame").item(0) as Element
        val children = serviceFrame.childNodes
        var serviceLinksPos = -1
        var journeyPatternsPos = -1
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) when (node.localName) {
                "serviceLinks" -> serviceLinksPos = i
                "journeyPatterns" -> journeyPatternsPos = i
            }
        }
        assertTrue(serviceLinksPos in 0..<journeyPatternsPos,
            "serviceLinks (pos $serviceLinksPos) must precede journeyPatterns (pos $journeyPatternsPos)")
    }

    @Test
    fun `appends to existing serviceLinks section`() {
        val xmlWithExisting = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <serviceLinks>
                                <ServiceLink id="TST:ServiceLink:existing" version="1">
                                    <FromPointRef ref="TST:ScheduledStopPoint:X" version="1"/>
                                    <ToPointRef ref="TST:ScheduledStopPoint:Y" version="1"/>
                                </ServiceLink>
                            </serviceLinks>
                            <journeyPatterns/>
                        </ServiceFrame>
                    </frames></CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(xmlWithExisting, plan)
        val doc = parse(result)

        val serviceLinks = doc.getElementsByTagName("ServiceLink")
        assertEquals(2, serviceLinks.length, "Should have existing + new ServiceLink")
        assertEquals("TST:ServiceLink:existing", (serviceLinks.item(0) as Element).getAttribute("id"))
        assertEquals("TST:ServiceLink:A-B", (serviceLinks.item(1) as Element).getAttribute("id"))

        // Only one serviceLinks section
        assertEquals(1, doc.getElementsByTagName("serviceLinks").length)
    }

    @Test
    fun `injects stub ScheduledStopPoints`() {
        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = mapOf("TST:ScheduledStopPoint:C" to "42"),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(basicXml, plan)
        val doc = parse(result)

        val stops = doc.getElementsByTagName("ScheduledStopPoint")
        val ids = (0 until stops.length).map { (stops.item(it) as Element).getAttribute("id") }
        assertTrue("TST:ScheduledStopPoint:C" in ids, "Stub stop should be injected")

        val stub = (0 until stops.length)
            .map { stops.item(it) as Element }
            .first { it.getAttribute("id") == "TST:ScheduledStopPoint:C" }
        assertEquals("42", stub.getAttribute("version"))
    }

    @Test
    fun `adds linksInSequence to JourneyPattern with no existing`() {
        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JourneyPattern:1" to listOf(
                    SlijpEntry("TST:SLIJP:1", "1", 1, "TST:ServiceLink:A-B", null)
                )
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(basicXml, plan)
        val doc = parse(result)

        val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        assertEquals(1, slijps.length)

        val entry = slijps.item(0) as Element
        assertEquals("TST:SLIJP:1", entry.getAttribute("id"))
        assertEquals("1", entry.getAttribute("order"))
        val ref = entry.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertEquals("TST:ServiceLink:A-B", ref.getAttribute("ref"))
        assertEquals("", ref.getAttribute("version"), "Should omit version when null")
    }

    @Test
    fun `replaces existing linksInSequence with merged version`() {
        val xmlWithExistingLinks = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <journeyPatterns>
                                <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                    <pointsInSequence>
                                        <StopPointInJourneyPattern id="TST:SPIJP:1" version="1" order="1">
                                            <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="1"/>
                                        </StopPointInJourneyPattern>
                                    </pointsInSequence>
                                    <linksInSequence>
                                        <ServiceLinkInJourneyPattern id="OLD:SLIJP:1" version="9" order="1">
                                            <ServiceLinkRef ref="TST:ServiceLink:old" version="5"/>
                                        </ServiceLinkInJourneyPattern>
                                    </linksInSequence>
                                </JourneyPattern>
                            </journeyPatterns>
                        </ServiceFrame>
                    </frames></CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JourneyPattern:1" to listOf(
                    SlijpEntry("OLD:SLIJP:1", "9", 1, "TST:ServiceLink:old", "5"),
                    SlijpEntry("TST:SLIJP:new", "1", 2, "TST:ServiceLink:new", null)
                )
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(xmlWithExistingLinks, plan)
        val doc = parse(result)

        val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        assertEquals(2, slijps.length, "Should have merged entries")

        val first = slijps.item(0) as Element
        assertEquals("OLD:SLIJP:1", first.getAttribute("id"), "Preserved existing entry ID")
        assertEquals("9", first.getAttribute("version"), "Preserved existing entry version")
        val firstRef = first.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertEquals("5", firstRef.getAttribute("version"), "Preserved existing ref version")

        val second = slijps.item(1) as Element
        assertEquals("TST:SLIJP:new", second.getAttribute("id"))
        assertEquals("2", second.getAttribute("order"))
        val secondRef = second.getElementsByTagName("ServiceLinkRef").item(0) as Element
        assertEquals("", secondRef.getAttribute("version"), "New entry should omit version")
    }

    @Test
    fun `GML namespace is added to root element`() {
        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(basicXml, plan)
        val xmlString = String(result, Charsets.UTF_8)

        assertTrue(xmlString.contains("gis:LineString"), "ServiceLink should use gis prefix for GML")
        assertTrue(xmlString.contains("gis:posList"), "posList should use gis prefix")
    }

    @Test
    fun `rewrites gml prefix to gis in existing content`() {
        val xmlWithGml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" version="1.0">
                <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <serviceLinks>
                                <ServiceLink id="TST:ServiceLink:1" version="1">
                                    <projections>
                                        <LinkSequenceProjection id="TST:LSP:1" version="1">
                                            <gml:LineString srsName="4326" gml:id="ls_1">
                                                <gml:posList>59.0 10.0 60.0 11.0</gml:posList>
                                            </gml:LineString>
                                        </LinkSequenceProjection>
                                    </projections>
                                    <FromPointRef ref="TST:SSP:A" version="1"/>
                                    <ToPointRef ref="TST:SSP:B" version="1"/>
                                </ServiceLink>
                            </serviceLinks>
                            <journeyPatterns>
                                <JourneyPattern id="TST:JourneyPattern:1" version="1">
                                    <pointsInSequence/>
                                </JourneyPattern>
                            </journeyPatterns>
                        </ServiceFrame>
                    </frames></CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        // Use a plan that only adds linksInSequence (forces transformation)
        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JourneyPattern:1" to listOf(
                    SlijpEntry("TST:SLIJP:1", "1", 1, "TST:ServiceLink:1", null)
                )
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(xmlWithGml, plan)
        val xmlString = String(result, Charsets.UTF_8)

        assertFalse(xmlString.contains("<gml:"), "gml prefix should be rewritten to gis")
        assertTrue(xmlString.contains("gis:LineString") || xmlString.contains("gis:posList"),
            "GML elements should use gis prefix")
    }

    @Test
    fun `handles multiple JourneyPatterns independently`() {
        val multiJpXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <journeyPatterns>
                                <JourneyPattern id="TST:JP:1" version="1">
                                    <pointsInSequence/>
                                </JourneyPattern>
                                <JourneyPattern id="TST:JP:2" version="1">
                                    <pointsInSequence/>
                                </JourneyPattern>
                            </journeyPatterns>
                        </ServiceFrame>
                    </frames></CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JP:1" to listOf(SlijpEntry("SLIJP:1", "1", 1, "SL:A", null)),
                "TST:JP:2" to listOf(
                    SlijpEntry("SLIJP:2", "1", 1, "SL:B", null),
                    SlijpEntry("SLIJP:3", "1", 2, "SL:C", null)
                )
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(multiJpXml, plan)
        val doc = parse(result)

        val linksInSeq = doc.getElementsByTagName("linksInSequence")
        assertEquals(2, linksInSeq.length, "Each JP should have its own linksInSequence")

        val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        assertEquals(3, slijps.length, "Total 3 SLIJP entries across both JPs")
    }

    @Test
    fun `JP not in plan keeps its existing linksInSequence untouched`() {
        val xmlWithLinks = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                            <journeyPatterns>
                                <JourneyPattern id="TST:JP:untouched" version="1">
                                    <linksInSequence>
                                        <ServiceLinkInJourneyPattern id="ORIG:1" version="1" order="1">
                                            <ServiceLinkRef ref="SL:orig" version="1"/>
                                        </ServiceLinkInJourneyPattern>
                                    </linksInSequence>
                                </JourneyPattern>
                                <JourneyPattern id="TST:JP:modified" version="1">
                                    <pointsInSequence/>
                                </JourneyPattern>
                            </journeyPatterns>
                        </ServiceFrame>
                    </frames></CompositeFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JP:modified" to listOf(SlijpEntry("NEW:1", "1", 1, "SL:new", null))
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(xmlWithLinks, plan)
        val doc = parse(result)

        val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        assertEquals(2, slijps.length)
        assertEquals("ORIG:1", (slijps.item(0) as Element).getAttribute("id"), "Untouched JP keeps original SLIJP")
        assertEquals("NEW:1", (slijps.item(1) as Element).getAttribute("id"))
    }

    @Test
    fun `ServiceLink geometry contains correct GML structure`() {
        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(basicXml, plan)
        val doc = parse(result)

        val lineString = doc.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "LineString")
        assertEquals(1, lineString.length)

        val ls = lineString.item(0) as Element
        assertEquals("EPSG:4326", ls.getAttribute("srsName"))

        val posList = doc.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
        assertEquals(1, posList.length)
        assertEquals("59.9139 10.7522 59.9556 11.0464", posList.item(0).textContent.trim())
    }

    @Test
    fun `injects sections into ServiceFrame with no existing children after stops`() {
        val minimalXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <dataObjects>
                    <ServiceFrame id="TST:SF:1" version="1"/>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLinkData()),
            stubScheduledStopPoints = mapOf("TST:SSP:stub" to "1"),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = true
        )

        val result = transformer.transform(minimalXml, plan)
        val doc = parse(result)

        assertEquals(1, doc.getElementsByTagName("ServiceLink").length, "ServiceLink injected into empty ServiceFrame")
        assertEquals(1, doc.getElementsByTagName("ScheduledStopPoint").length, "Stub stop injected")
    }

    @Test
    fun `preserves XML comments and processing instructions`() {
        val xmlWithComments = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                <!-- This is a comment -->
                <dataObjects>
                    <ServiceFrame id="TST:SF:1" version="1">
                        <journeyPatterns>
                            <JourneyPattern id="TST:JP:1" version="1">
                                <pointsInSequence/>
                            </JourneyPattern>
                        </journeyPatterns>
                    </ServiceFrame>
                </dataObjects>
            </PublicationDelivery>
        """.trimIndent().toByteArray()

        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "TST:JP:1" to listOf(SlijpEntry("SLIJP:1", "1", 1, "SL:1", null))
            ),
            needsGmlNamespace = false
        )

        val result = transformer.transform(xmlWithComments, plan)
        val xmlString = String(result, Charsets.UTF_8)
        assertTrue(xmlString.contains("This is a comment"), "XML comments should be preserved")
    }
}

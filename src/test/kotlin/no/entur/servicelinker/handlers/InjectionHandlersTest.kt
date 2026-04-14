package no.entur.servicelinker.handlers

import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import org.entur.netex.tools.lib.NetexProcessor
import org.entur.netex.tools.lib.config.FilterConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for the handler-based injection pipeline, exercising scenarios
 * that aren't covered by the end-to-end NetexProcessorTest.
 */
class InjectionHandlersTest {

    private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    private fun parse(xml: ByteArray): Document =
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private fun applyViaHandlers(xml: String, plan: InjectionPlan): ByteArray {
        val handlers = buildHandlerMap(plan)
        val config = FilterConfig(
            customElementHandlers = handlers,
            elementsRequiredChildren = if (plan.journeyPatternLinks.isNotEmpty())
                mapOf("linksInSequence" to listOf("ServiceLinkInJourneyPattern"))
            else emptyMap(),
        )
        val processor = NetexProcessor(filterConfig = config)
        val docs = mapOf("test.xml" to xml.toByteArray())
        processor.buildEntityModel(docs)

        // Exclude SLIJP entities for JPs with planned replacements
        val slijpIdsToExclude = processor.model
            .getEntitiesOfType("ServiceLinkInJourneyPattern")
            .filter { plan.journeyPatternLinks.containsKey(it.parent?.id) }
            .map { it.id }
            .toSet()

        val (es, rs) = processor.selectEntities()
        val modifiedSelection = if (slijpIdsToExclude.isNotEmpty()) {
            val slijpMap = es.selection["ServiceLinkInJourneyPattern"]
            if (slijpMap != null) es.withReplaced("ServiceLinkInJourneyPattern",
                slijpMap.filterKeys { it !in slijpIdsToExclude })
            else es
        } else es

        val result = processor.exportToByteArrays(docs, modifiedSelection, rs)
        return requireNotNull(result.documents["test.xml"])
    }

    // -----------------------------------------------------------------------
    // Test gap #6: JP not in plan keeps its existing linksInSequence
    // -----------------------------------------------------------------------

    @Test
    fun `JP not in plan keeps its existing linksInSequence untouched`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
              <dataObjects>
                <CompositeFrame id="CF:1" version="1"><frames>
                  <ServiceFrame id="SF:1" version="1">
                    <journeyPatterns>
                      <JourneyPattern id="JP:modified" version="1">
                        <linksInSequence>
                          <ServiceLinkInJourneyPattern id="OLD:1" version="1" order="1">
                            <ServiceLinkRef ref="SL:old" version="1"/>
                          </ServiceLinkInJourneyPattern>
                        </linksInSequence>
                      </JourneyPattern>
                      <JourneyPattern id="JP:untouched" version="1">
                        <linksInSequence>
                          <ServiceLinkInJourneyPattern id="KEEP:1" version="1" order="1">
                            <ServiceLinkRef ref="SL:keep" version="1"/>
                          </ServiceLinkInJourneyPattern>
                          <ServiceLinkInJourneyPattern id="KEEP:2" version="1" order="2">
                            <ServiceLinkRef ref="SL:keep2" version="1"/>
                          </ServiceLinkInJourneyPattern>
                        </linksInSequence>
                      </JourneyPattern>
                    </journeyPatterns>
                  </ServiceFrame>
                </frames></CompositeFrame>
              </dataObjects>
            </PublicationDelivery>
        """.trimIndent()

        val plan = InjectionPlan(
            newServiceLinks = emptyList(),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = mapOf(
                "JP:modified" to listOf(
                    SlijpEntry("NEW:1", "1", 1, "SL:new", null)
                )
            ),
            needsGmlNamespace = false
        )

        val result = applyViaHandlers(xml, plan)
        val doc = parse(result)

        val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
        val ids = (0 until slijps.length).map { (slijps.item(it) as Element).getAttribute("id") }

        // Modified JP: old entry replaced with new
        assertTrue("NEW:1" in ids, "Modified JP should have new SLIJP")
        assertFalse("OLD:1" in ids, "Modified JP's old SLIJP should be removed")

        // Untouched JP: both entries preserved
        assertTrue("KEEP:1" in ids, "Untouched JP should keep KEEP:1")
        assertTrue("KEEP:2" in ids, "Untouched JP should keep KEEP:2")

        // Verify 2 linksInSequence elements (one per JP)
        assertEquals(2, doc.getElementsByTagName("linksInSequence").length)
    }

    // -----------------------------------------------------------------------
    // Test gap #7: non-CompositeFrame ServiceFrame path
    // -----------------------------------------------------------------------

    @Test
    fun `injection works with ServiceFrame directly under dataObjects`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
              <dataObjects>
                <ServiceFrame id="SF:1" version="1">
                  <scheduledStopPoints>
                    <ScheduledStopPoint id="SSP:A" version="1"/>
                  </scheduledStopPoints>
                  <journeyPatterns>
                    <JourneyPattern id="JP:1" version="1">
                      <pointsInSequence/>
                    </JourneyPattern>
                  </journeyPatterns>
                </ServiceFrame>
              </dataObjects>
            </PublicationDelivery>
        """.trimIndent()

        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLink()),
            stubScheduledStopPoints = mapOf("SSP:B" to "1"),
            journeyPatternLinks = mapOf(
                "JP:1" to listOf(SlijpEntry("SLIJP:1", "1", 1, "SL:A-B", null))
            ),
            needsGmlNamespace = false
        )

        val result = applyViaHandlers(xml, plan)
        val doc = parse(result)

        // ServiceLink injected
        assertEquals(1, doc.getElementsByTagName("ServiceLink").length)
        // Stub stop injected
        val stops = doc.getElementsByTagName("ScheduledStopPoint")
        val stopIds = (0 until stops.length).map { (stops.item(it) as Element).getAttribute("id") }
        assertTrue("SSP:B" in stopIds, "Stub stop should be injected")
        // linksInSequence emitted
        assertEquals(1, doc.getElementsByTagName("ServiceLinkInJourneyPattern").length)

        // Schema ordering: scheduledStopPoints < serviceLinks < journeyPatterns
        val sf = doc.getElementsByTagName("ServiceFrame").item(0) as Element
        val children = sf.childNodes
        var stopsPos = -1; var linksPos = -1; var jpPos = -1
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) when (node.localName) {
                "scheduledStopPoints" -> stopsPos = i
                "serviceLinks" -> linksPos = i
                "journeyPatterns" -> jpPos = i
            }
        }
        assertTrue(stopsPos < linksPos, "stops before links")
        assertTrue(linksPos < jpPos, "links before journeyPatterns")
    }

    // -----------------------------------------------------------------------
    // Test gap #8: comment preservation
    // -----------------------------------------------------------------------

    @Test
    fun `XML comments are preserved in output`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
              <!-- top-level comment -->
              <dataObjects>
                <CompositeFrame id="CF:1" version="1"><frames>
                  <ServiceFrame id="SF:1" version="1">
                    <!-- comment inside ServiceFrame -->
                    <journeyPatterns>
                      <JourneyPattern id="JP:1" version="1">
                        <pointsInSequence/>
                      </JourneyPattern>
                    </journeyPatterns>
                  </ServiceFrame>
                </frames></CompositeFrame>
              </dataObjects>
            </PublicationDelivery>
        """.trimIndent()

        val plan = InjectionPlan(
            newServiceLinks = listOf(sampleServiceLink()),
            stubScheduledStopPoints = emptyMap(),
            journeyPatternLinks = emptyMap(),
            needsGmlNamespace = false
        )

        val result = applyViaHandlers(xml, plan)
        val output = String(result, Charsets.UTF_8)

        assertTrue(output.contains("top-level comment"), "Top-level comment should be preserved")
        assertTrue(output.contains("comment inside ServiceFrame"), "ServiceFrame comment should be preserved")
    }

    private fun sampleServiceLink() = ServiceLinkData(
        id = "SL:A-B", version = "1", distance = 1000,
        projectionId = "LSP:1", gmlLineStringId = "ls_1",
        posList = "59.0 10.0 60.0 11.0",
        fromPointRef = "SSP:A", fromPointVersion = "1",
        toPointRef = "SSP:B", toPointVersion = "1"
    )
}

package no.entur.servicelinker.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.IncompleteJourneyPatternException
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.plugins.MetadataExtractor
import no.entur.servicelinker.util.PolylineEncoding
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Detailed regression tests that verify the StAX transformer produces
 * output structurally identical to what the old DOM-based approach produced.
 *
 * These tests check every detail: element content, attribute values,
 * namespace declarations, element ordering, whitespace-insensitive text content,
 * and round-trip integrity through ZIP processing.
 */
class StaxOutputRegressionTest {
    private lateinit var mockOsrmService: OSRMService
    private lateinit var netexProcessor: NetexProcessor

    private val oslo = Coordinate(59.9139, 10.7522)
    private val lillestrøm = Coordinate(59.9556, 11.0464)
    private val jessheim = Coordinate(60.1450, 11.1740)

    private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    @BeforeEach
    fun setup() {
        mockOsrmService = mockk(relaxed = true)
        netexProcessor = NetexProcessor(mockOsrmService, MetadataExtractor(), ServicelinkerMetrics(SimpleMeterRegistry()))

        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } returns
            OSRMRoute(
                distance = 15000.0,
                duration = 1200.0,
                geometry = PolylineEncoding.encode(listOf(oslo, lillestrøm)),
            )
    }

    private fun parse(bytes: ByteArray): Document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun getSampleNetexBytes(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/sample-netex.xml")).readBytes()

    // --- Full output verification for sample-netex.xml ---

    @Test
    fun `sample-netex full output verification`() =
        runTest {
            val inputBytes = getSampleNetexBytes()
            val (outputBytes, addedCount) = netexProcessor.processNetexFile(inputBytes)

            assertEquals(1, addedCount, "Expected exactly 1 new ServiceLink (2→3)")

            val doc = parse(outputBytes)
            val xmlString = String(outputBytes, Charsets.UTF_8)

            // --- XML declaration ---
            assertTrue(xmlString.trimStart().startsWith("<?xml"), "Must start with XML declaration")

            // --- Root element and namespace ---
            val root = doc.documentElement
            assertEquals("PublicationDelivery", root.localName)
            assertEquals("http://www.netex.org.uk/netex", root.namespaceURI)

            // --- GML namespace must be declared with gis prefix ---
            assertTrue(
                xmlString.contains("xmlns:gis=\"http://www.opengis.net/gml/3.2\""),
                "GML namespace must be declared with gis prefix",
            )
            assertFalse(
                xmlString.contains("xmlns:gml="),
                "No gml prefix declarations should remain",
            )

            // --- All original content preserved ---
            assertEquals(
                "2025-09-05T10:00:00Z",
                doc
                    .getElementsByTagName("PublicationTimestamp")
                    .item(0)
                    ?.textContent
                    ?.trim(),
            )
            assertEquals(1, doc.getElementsByTagName("Operator").length, "Operator preserved")
            assertEquals(1, doc.getElementsByTagName("Authority").length, "Authority preserved")
            assertEquals(1, doc.getElementsByTagName("Network").length, "Network preserved")
            assertEquals(1, doc.getElementsByTagName("Route").length, "Route preserved")
            assertEquals(1, doc.getElementsByTagName("Line").length, "Line preserved")
            assertEquals(
                1,
                doc.getElementsByTagName("DestinationDisplay").length,
                "DestinationDisplay preserved",
            )

            // --- ScheduledStopPoints unchanged ---
            val stops = doc.getElementsByTagName("ScheduledStopPoint")
            assertEquals(3, stops.length, "All 3 ScheduledStopPoints preserved")
            val stopIds = (0 until stops.length).map { (stops.item(it) as Element).getAttribute("id") }
            assertEquals(
                listOf("FIN:ScheduledStopPoint:1", "FIN:ScheduledStopPoint:2", "FIN:ScheduledStopPoint:3"),
                stopIds,
            )

            // --- ServiceLinks: 1 existing + 1 new = 2 total ---
            val serviceLinks = doc.getElementsByTagName("ServiceLink")
            assertEquals(2, serviceLinks.length)

            // Existing ServiceLink preserved exactly
            val existingLink = serviceLinks.item(0) as Element
            assertEquals("FIN:ServiceLink:1", existingLink.getAttribute("id"))
            assertEquals("1", existingLink.getAttribute("version"))
            assertEquals("8286", (existingLink.getElementsByTagName("Distance").item(0) as Element).textContent.trim())
            val existingFrom = existingLink.getElementsByTagName("FromPointRef").item(0) as Element
            assertEquals("FIN:ScheduledStopPoint:1", existingFrom.getAttribute("ref"))
            assertEquals("1", existingFrom.getAttribute("version"))
            val existingTo = existingLink.getElementsByTagName("ToPointRef").item(0) as Element
            assertEquals("FIN:ScheduledStopPoint:2", existingTo.getAttribute("ref"))

            // Existing GML content preserved (prefix rewritten to gis)
            val existingPosLists = existingLink.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
            assertEquals(1, existingPosLists.length, "Existing posList preserved")
            assertTrue(
                existingPosLists.item(0).textContent.contains("70.986542"),
                "Existing posList coordinate data preserved",
            )

            // New ServiceLink structure verified
            val newLink = serviceLinks.item(1) as Element
            assertEquals("FIN:ServiceLink:2-3", newLink.getAttribute("id"))
            assertEquals("1", newLink.getAttribute("version"))
            assertEquals("15000", (newLink.getElementsByTagName("Distance").item(0) as Element).textContent.trim())

            val newFrom = newLink.getElementsByTagName("FromPointRef").item(0) as Element
            assertEquals("FIN:ScheduledStopPoint:2", newFrom.getAttribute("ref"))
            assertEquals("1", newFrom.getAttribute("version"))
            val newTo = newLink.getElementsByTagName("ToPointRef").item(0) as Element
            assertEquals("FIN:ScheduledStopPoint:3", newTo.getAttribute("ref"))
            assertEquals("1", newTo.getAttribute("version"))

            // New GML geometry
            val newLineString = newLink.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "LineString")
            assertEquals(1, newLineString.length)
            val ls = newLineString.item(0) as Element
            assertEquals("EPSG:4326", ls.getAttribute("srsName"))

            val newPosList = newLink.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
            assertEquals(1, newPosList.length)
            val posListValues =
                newPosList
                    .item(0)
                    .textContent
                    .trim()
                    .split("\\s+".toRegex())
                    .map { it.toDouble() }
            // Start at stop 2 coords, end at stop 3 coords
            assertEquals(59.9556, posListValues[0], 0.001, "posList starts at stop 2 latitude")
            assertEquals(11.0464, posListValues[1], 0.001, "posList starts at stop 2 longitude")

            // LinkSequenceProjection
            val projections = newLink.getElementsByTagName("LinkSequenceProjection")
            assertEquals(1, projections.length)
            val proj = projections.item(0) as Element
            assertTrue(
                proj.getAttribute("id").startsWith("FIN:LinkSequenceProjection:"),
                "Projection ID uses correct codespace",
            )
            assertEquals("1", proj.getAttribute("version"))

            // --- ServiceFrame element ordering ---
            val serviceFrame = doc.getElementsByTagName("ServiceFrame").item(0) as Element
            val sfChildren = mutableListOf<String>()
            for (i in 0 until serviceFrame.childNodes.length) {
                val child = serviceFrame.childNodes.item(i)
                if (child is Element) sfChildren.add(child.localName)
            }
            val scheduledStopPointsIdx = sfChildren.indexOf("scheduledStopPoints")
            val serviceLinksIdx = sfChildren.indexOf("serviceLinks")
            val journeyPatternsIdx = sfChildren.indexOf("journeyPatterns")
            assertTrue(scheduledStopPointsIdx >= 0, "scheduledStopPoints present")
            assertTrue(serviceLinksIdx >= 0, "serviceLinks present")
            assertTrue(journeyPatternsIdx >= 0, "journeyPatterns present")
            assertTrue(
                scheduledStopPointsIdx < serviceLinksIdx,
                "scheduledStopPoints ($scheduledStopPointsIdx) before serviceLinks ($serviceLinksIdx)",
            )
            assertTrue(
                serviceLinksIdx < journeyPatternsIdx,
                "serviceLinks ($serviceLinksIdx) before journeyPatterns ($journeyPatternsIdx)",
            )

            // --- linksInSequence: merged existing + new ---
            val linksInSeq = doc.getElementsByTagName("linksInSequence")
            assertEquals(1, linksInSeq.length)

            val slijps = doc.getElementsByTagName("ServiceLinkInJourneyPattern")
            assertEquals(2, slijps.length, "2 SLIJP entries (existing 1→2 + new 2→3)")

            // First entry: preserved from existing
            val slijp1 = slijps.item(0) as Element
            assertEquals(
                "FIN:ServiceLinkInJourneyPattern:1_to_2",
                slijp1.getAttribute("id"),
                "Existing SLIJP ID preserved",
            )
            assertEquals("1", slijp1.getAttribute("version"), "Existing SLIJP version preserved")
            assertEquals("1", slijp1.getAttribute("order"))
            val slijp1Ref = slijp1.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertEquals("FIN:ServiceLink:1", slijp1Ref.getAttribute("ref"))
            assertEquals("1", slijp1Ref.getAttribute("version"), "Existing ServiceLinkRef version preserved")

            // Second entry: newly created
            val slijp2 = slijps.item(1) as Element
            assertEquals("FIN:ServiceLinkInJourneyPattern:1_2_to_3", slijp2.getAttribute("id"))
            assertEquals("1", slijp2.getAttribute("version"))
            assertEquals("2", slijp2.getAttribute("order"))
            val slijp2Ref = slijp2.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertEquals("FIN:ServiceLink:2-3", slijp2Ref.getAttribute("ref"))
            assertEquals(
                "",
                slijp2Ref.getAttribute("version"),
                "New ServiceLinkRef should omit version for cross-file compatibility",
            )

            // --- JourneyPattern structure preserved ---
            val jp = doc.getElementsByTagName("JourneyPattern").item(0) as Element
            assertEquals("FIN:JourneyPattern:1", jp.getAttribute("id"))
            val pointsInSeq = jp.getElementsByTagName("pointsInSequence")
            assertEquals(1, pointsInSeq.length, "pointsInSequence preserved")
            val spijps = jp.getElementsByTagName("StopPointInJourneyPattern")
            assertEquals(3, spijps.length, "All 3 StopPointInJourneyPattern preserved")
        }

    // --- Round-trip: process → re-parse → verify parseable ---

    @Test
    fun `output is well-formed XML that can be re-parsed`() =
        runTest {
            val (outputBytes, _) =
                netexProcessor.processNetexFile(getSampleNetexBytes())

            // Must parse without error
            val doc = parse(outputBytes)
            assertNotNull(doc.documentElement)
            assertEquals("PublicationDelivery", doc.documentElement.localName)
        }

    @Test
    fun `idempotent processing produces no additional changes`() =
        runTest {
            val inputBytes = getSampleNetexBytes()
            val (firstPass, firstCount) = netexProcessor.processNetexFile(inputBytes)
            assertEquals(1, firstCount)

            // Process the output again — should add 0 new links
            val (secondPass, secondCount) = netexProcessor.processNetexFile(firstPass)
            assertEquals(0, secondCount, "Second pass should add no new service links")

            // Output should be identical to input (returned unchanged when addedCount=0)
            assertArrayEquals(
                firstPass,
                secondPass,
                "Idempotent: second pass output should be byte-identical to first pass output",
            )
        }

    // --- ZIP round-trip ---

    @Test
    fun `ZIP processing produces parseable XML in every entry`() =
        runTest {
            val mockGcsService = mockk<GcsService>(relaxed = true)
            val mockNsrService = mockk<NsrService>(relaxed = true)
            val service =
                ServiceLinkProducerService(
                    mockGcsService,
                    netexProcessor,
                    mockOsrmService,
                    mockNsrService,
                    MetadataExtractor(),
                    "test-output",
                )

            val sampleXml = getSampleNetexBytes()
            val zipData =
                ByteArrayOutputStream()
                    .also { buf ->
                        ZipOutputStream(buf).use { zos ->
                            zos.putNextEntry(ZipEntry("shared_data.xml"))
                            zos.write(sampleXml)
                            zos.closeEntry()
                            zos.putNextEntry(ZipEntry("line1.xml"))
                            zos.write(sampleXml)
                            zos.closeEntry()
                        }
                    }.toByteArray()

            val (processedZip, addedCount) = service.processZipDirectly(zipData)
            assertTrue(addedCount > 0, "Should have added service links")

            // Every XML entry in the output ZIP must be parseable
            ZipInputStream(ByteArrayInputStream(processedZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".xml")) {
                        val entryBytes = zis.readBytes()
                        val doc = parse(entryBytes)
                        assertNotNull(
                            doc.documentElement,
                            "Entry ${entry.name} must produce a valid DOM",
                        )
                        assertEquals(
                            "PublicationDelivery",
                            doc.documentElement.localName,
                            "Entry ${entry.name} must have PublicationDelivery root",
                        )

                        // Every entry should have its linksInSequence updated
                        val jps = doc.getElementsByTagName("JourneyPattern")
                        for (i in 0 until jps.length) {
                            val jp = jps.item(i) as Element
                            val lis = jp.getElementsByTagName("linksInSequence")
                            assertTrue(
                                lis.length > 0,
                                "JourneyPattern ${jp.getAttribute("id")} in ${entry.name} should have linksInSequence",
                            )
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

    // --- Havila test data round-trip ---

    @Test
    fun `Havila test data ZIP round-trip produces valid output`() =
        runTest {
            val havilaZip = javaClass.getResourceAsStream("/../../test-data/Havila_NeTEx_20260219.zip")
            org.junit.jupiter.api.Assumptions.assumeTrue(
                havilaZip != null,
                "Skipping: test-data/Havila_NeTEx_20260219.zip not found",
            )

            val mockGcsService = mockk<GcsService>(relaxed = true)
            val mockNsrService = mockk<NsrService>(relaxed = true)
            // Mock returns a coord for every queried quay so the integrity check passes and the
            // test can focus on its real concern: every output ZIP entry must be parseable XML.
            val fakeCoord = Coordinate(BigDecimal("60.0"), BigDecimal("10.0"))
            coEvery {
                mockNsrService.lookupQuayCoordinates(any<Collection<String>>())
            } answers { NsrLookupResult(firstArg<Collection<String>>().associateWith { fakeCoord }, emptySet()) }
            val service =
                ServiceLinkProducerService(
                    mockGcsService,
                    netexProcessor,
                    mockOsrmService,
                    mockNsrService,
                    MetadataExtractor(),
                    "test-output",
                )

            val zipData = havilaZip.readBytes()
            val (processedZip, addedCount) = service.processZipDirectly(zipData)
            assertTrue(addedCount >= 0, "Processing should complete without error")

            // Collect entry names and sizes from input and output
            val inputEntries = mutableMapOf<String, Int>()
            ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    inputEntries[entry.name] = zis.readBytes().size
                    entry = zis.nextEntry
                }
            }

            val outputEntries = mutableMapOf<String, Int>()
            ZipInputStream(ByteArrayInputStream(processedZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryBytes = zis.readBytes()
                    outputEntries[entry.name] = entryBytes.size

                    if (entry.name.endsWith(".xml")) {
                        // Must parse without error
                        val doc = parse(entryBytes)
                        assertNotNull(
                            doc.documentElement,
                            "Entry ${entry.name} must be valid XML after processing",
                        )
                    }
                    entry = zis.nextEntry
                }
            }

            assertEquals(
                inputEntries.keys,
                outputEntries.keys,
                "Output ZIP must have the same entries as input ZIP",
            )
        }

    // --- GML prefix rewriting regression ---

    @Test
    fun `gml prefix in existing content is fully rewritten to gis in output`() =
        runTest {
            // Replace gis with gml in the sample to simulate upstream gml-prefixed input
            val inputWithGml =
                String(getSampleNetexBytes(), Charsets.UTF_8)
                    .replace("xmlns:gis=", "xmlns:gml=")
                    .replace("gis:", "gml:")

            assertTrue(
                inputWithGml.contains("xmlns:gml=\"http://www.opengis.net/gml/3.2\""),
                "Test setup: input must use gml prefix",
            )

            val (outputBytes, addedCount) = netexProcessor.processNetexFile(inputWithGml.toByteArray())
            assertEquals(1, addedCount)

            val xmlString = String(outputBytes, Charsets.UTF_8)

            // No gml prefix anywhere in output
            assertFalse(xmlString.contains("xmlns:gml="), "No xmlns:gml declarations")
            assertFalse(xmlString.contains("<gml:"), "No gml: prefixed start tags")
            assertFalse(xmlString.contains("</gml:"), "No gml: prefixed end tags")

            // gis prefix used instead
            assertTrue(xmlString.contains("xmlns:gis=\"http://www.opengis.net/gml/3.2\""))

            // Both existing and new GML elements use gis prefix
            val doc = parse(outputBytes)
            val lineStrings = doc.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "LineString")
            assertEquals(2, lineStrings.length, "2 LineStrings: 1 existing + 1 new")

            val posLists = doc.getElementsByTagNameNS("http://www.opengis.net/gml/3.2", "posList")
            assertEquals(2, posLists.length, "2 posLists: 1 existing + 1 new")
        }

    // --- Cross-file ServiceLink placement regression ---

    @Test
    fun `cross-file stub stops get correct version and placement`() =
        runTest {
            val mockGcsService = mockk<GcsService>(relaxed = true)
            val mockNsrService = mockk<NsrService>(relaxed = true)
            coEvery {
                mockNsrService.lookupQuayCoordinates(any<Collection<String>>())
            } returns NsrLookupResult(mapOf("NSR:Quay:1" to oslo, "NSR:Quay:2" to lillestrøm), emptySet())
            val service =
                ServiceLinkProducerService(
                    mockGcsService,
                    netexProcessor,
                    mockOsrmService,
                    mockNsrService,
                    MetadataExtractor(),
                    "test-output",
                )

            val stopVersion = "20260212093839"
            val sharedDataXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1"><frames>
                      <ServiceFrame id="TST:ServiceFrame:1" version="1">
                        <scheduledStopPoints>
                          <ScheduledStopPoint version="$stopVersion" id="TST:ScheduledStopPoint:A">
                            <Name>Stop A</Name>
                          </ScheduledStopPoint>
                          <ScheduledStopPoint version="$stopVersion" id="TST:ScheduledStopPoint:B">
                            <Name>Stop B</Name>
                          </ScheduledStopPoint>
                        </scheduledStopPoints>
                        <stopAssignments>
                          <PassengerStopAssignment version="1" id="TST:PSA:A" order="1">
                            <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="$stopVersion"/>
                            <QuayRef ref="NSR:Quay:1" version="1"/>
                          </PassengerStopAssignment>
                          <PassengerStopAssignment version="1" id="TST:PSA:B" order="2">
                            <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="$stopVersion"/>
                            <QuayRef ref="NSR:Quay:2" version="1"/>
                          </PassengerStopAssignment>
                        </stopAssignments>
                      </ServiceFrame>
                    </frames></CompositeFrame>
                  </dataObjects>
                </PublicationDelivery>
                """.trimIndent().toByteArray()

            val lineXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:2" version="1"><frames>
                      <ServiceFrame id="TST:ServiceFrame:2" version="1">
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
                    </frames></CompositeFrame>
                  </dataObjects>
                </PublicationDelivery>
                """.trimIndent().toByteArray()

            val zipData =
                ByteArrayOutputStream()
                    .also { buf ->
                        ZipOutputStream(buf).use { zos ->
                            zos.putNextEntry(ZipEntry("_shared_data.xml"))
                            zos.write(sharedDataXml)
                            zos.closeEntry()
                            zos.putNextEntry(ZipEntry("line.xml"))
                            zos.write(lineXml)
                            zos.closeEntry()
                        }
                    }.toByteArray()

            val (processedZip, addedCount) = service.processZipDirectly(zipData)
            assertEquals(1, addedCount)

            val outputFiles = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(processedZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    outputFiles[entry.name] = zis.readBytes()
                    entry = zis.nextEntry
                }
            }

            // Shared data: ServiceLink placed here (from-stop declared here)
            val sharedDoc = parse(outputFiles.getValue("_shared_data.xml"))
            val sharedServiceLinks = sharedDoc.getElementsByTagName("ServiceLink")
            assertEquals(1, sharedServiceLinks.length, "ServiceLink in shared_data")

            // Verify FromPointRef/ToPointRef use stop's actual version
            val sl = sharedServiceLinks.item(0) as Element
            val fromRef = sl.getElementsByTagName("FromPointRef").item(0) as Element
            val toRef = sl.getElementsByTagName("ToPointRef").item(0) as Element
            assertEquals(
                stopVersion,
                fromRef.getAttribute("version"),
                "FromPointRef must use ScheduledStopPoint's actual version",
            )
            assertEquals(
                stopVersion,
                toRef.getAttribute("version"),
                "ToPointRef must use ScheduledStopPoint's actual version",
            )

            // Line file: linksInSequence added, no ServiceLinks
            val lineDoc = parse(outputFiles.getValue("line.xml"))
            assertEquals(
                0,
                lineDoc.getElementsByTagName("ServiceLink").length,
                "No ServiceLinks in line file",
            )
            assertEquals(
                1,
                lineDoc.getElementsByTagName("ServiceLinkInJourneyPattern").length,
                "linksInSequence added to JP in line file",
            )

            // Verify ServiceLinkRef omits version (cross-file compatibility)
            val slijpRef = lineDoc.getElementsByTagName("ServiceLinkRef").item(0) as Element
            assertEquals(
                "",
                slijpRef.getAttribute("version"),
                "Cross-file ServiceLinkRef must omit version",
            )
        }

    // --- Direct buildInjectionPlan unit tests ---

    @Test
    fun `buildInjectionPlan merges existing and new SLIJP entries correctly`() {
        val jpId = "TST:JourneyPattern:1"
        val existing =
            mapOf(
                jpId to
                    mapOf(
                        "SL:existing" to ExistingSlijpInfo("SLIJP:orig", "5", "2"),
                    ),
            )
        val allServiceLinks =
            mapOf(
                "TST:SSP:A_TST:SSP:B" to "SL:existing",
                "TST:SSP:B_TST:SSP:C" to "SL:new",
            )
        val jpStopSequences =
            mapOf(
                jpId to listOf("TST:SSP:A", "TST:SSP:B", "TST:SSP:C"),
            )

        val plan =
            netexProcessor.buildInjectionPlan(
                newServiceLinkData =
                    mapOf(
                        "TST:SSP:B_TST:SSP:C" to
                            ServiceLinkData(
                                "SL:new",
                                "1",
                                1000,
                                "P:1",
                                "ls:1",
                                "59.0 10.0",
                                "TST:SSP:B",
                                "1",
                                "TST:SSP:C",
                                "1",
                            ),
                    ),
                allServiceLinks = allServiceLinks,
                jpStopSequences = jpStopSequences,
                existingSlijpEntries = existing,
                declaredStopIds = setOf("TST:SSP:A", "TST:SSP:B", "TST:SSP:C"),
            )

        // JP should have 2 merged entries
        val jpLinks = requireNotNull(plan.journeyPatternLinks[jpId])
        assertEquals(2, jpLinks.size)

        // First: existing entry preserved
        assertEquals("SLIJP:orig", jpLinks[0].id)
        assertEquals("5", jpLinks[0].version)
        assertEquals("2", jpLinks[0].serviceLinkRefVersion)
        assertEquals(1, jpLinks[0].order)

        // Second: new entry created
        assertEquals("1", jpLinks[1].version)
        assertNull(jpLinks[1].serviceLinkRefVersion, "New entry should omit ref version")
        assertEquals(2, jpLinks[1].order)
    }

    @Test
    fun `buildInjectionPlan computes stub stops for cross-file references`() {
        val plan =
            netexProcessor.buildInjectionPlan(
                newServiceLinkData =
                    mapOf(
                        "A_B" to
                            ServiceLinkData(
                                "SL:1",
                                "1",
                                null,
                                "P:1",
                                "ls:1",
                                "59.0 10.0",
                                "SSP:A",
                                "1",
                                "SSP:B",
                                "42",
                            ),
                    ),
                allServiceLinks = mapOf("A_B" to "SL:1"),
                jpStopSequences = emptyMap(),
                existingSlijpEntries = emptyMap(),
                declaredStopIds = setOf("SSP:A"), // B is NOT declared
            )

        assertEquals(1, plan.stubScheduledStopPoints.size)
        assertEquals(
            "42",
            plan.stubScheduledStopPoints["SSP:B"],
            "Stub version should match the ServiceLink's ToPointRef version",
        )
        assertFalse(
            plan.stubScheduledStopPoints.containsKey("SSP:A"),
            "Already-declared stop should not get a stub",
        )
    }

    @Test
    fun `buildInjectionPlan throws when a JP pair has no service link`() {
        // Defense-in-depth: callers should run checkJourneyPatternIntegrity first; if they don't,
        // buildInjectionPlan refuses to emit a JP with fewer SLIJPs than consecutive stop pairs.
        val ex =
            assertThrows(IllegalStateException::class.java) {
                netexProcessor.buildInjectionPlan(
                    newServiceLinkData = emptyMap(),
                    allServiceLinks = mapOf("SSP:A_SSP:B" to "SL:1"), // only A→B, not B→C
                    jpStopSequences = mapOf("TST:JP:1" to listOf("SSP:A", "SSP:B", "SSP:C")),
                    existingSlijpEntries = emptyMap(),
                    declaredStopIds = emptySet(),
                )
            }
        assertTrue(ex.message!!.contains("SSP:B_SSP:C"), "Error should name the missing pair")
        assertTrue(ex.message!!.contains("TST:JP:1"), "Error should name the JP")
    }

    @Test
    fun `buildInjectionPlan produces empty plan when there are no JPs to process`() {
        val plan =
            netexProcessor.buildInjectionPlan(
                newServiceLinkData = emptyMap(),
                allServiceLinks = emptyMap(),
                jpStopSequences = emptyMap(),
                existingSlijpEntries = emptyMap(),
                declaredStopIds = emptySet(),
            )

        assertTrue(plan.isEmpty, "Plan should be empty when there are no JPs to process")
    }

    @Test
    fun `checkJourneyPatternIntegrity throws with details when a pair is missing`() {
        val ex =
            assertThrows(IncompleteJourneyPatternException::class.java) {
                NetexProcessor.checkJourneyPatternIntegrity(
                    jpStopSequences = mapOf("JP:1" to listOf("SSP:A", "SSP:B", "SSP:C")),
                    allServiceLinks = mapOf("SSP:A_SSP:B" to "SL:1"),
                )
            }
        assertEquals(1, ex.missingPairs.size)
        assertEquals("JP:1", ex.missingPairs[0].jpId)
        assertEquals("SSP:B", ex.missingPairs[0].fromRef)
        assertEquals("SSP:C", ex.missingPairs[0].toRef)
    }

    @Test
    fun `integrity error blames only the quay whose NSR coordinate actually failed to resolve`() {
        // JP A -> B -> C. Only B's NSR lookup failed, so B is absent from the enriched stop map
        // while A and C resolved. Both pairs (A->B, B->C) are missing because they hinge on B, but
        // only B's quay should be reported as unresolved — A and C are implicated purely by
        // adjacency and must not be blamed for a failed NSR lookup they didn't have.
        val coord = Coordinate(BigDecimal("60.0"), BigDecimal("10.0"))
        val ex =
            assertThrows(IncompleteJourneyPatternException::class.java) {
                NetexProcessor.checkJourneyPatternIntegrity(
                    jpStopSequences = mapOf("JP:1" to listOf("SSP:A", "SSP:B", "SSP:C")),
                    allServiceLinks = emptyMap(),
                    stopPoints =
                        mapOf(
                            "SSP:A" to StopPoint("SSP:A", null, coord, null, null),
                            "SSP:C" to StopPoint("SSP:C", null, coord, null, null),
                            // SSP:B absent — its NSR lookup failed
                        ),
                    stopAssignments =
                        mapOf(
                            "SSP:A" to "NSR:Quay:1",
                            "SSP:B" to "NSR:Quay:2",
                            "SSP:C" to "NSR:Quay:3",
                        ),
                )
            }
        val unresolvedLine =
            ex.message!!.lineSequence().firstOrNull { it.contains("Quays with PSA") }
                ?: fail("integrity message should list the quays with PSA but no coordinate")
        assertTrue(unresolvedLine.contains("NSR:Quay:2"), "The genuinely-unresolved quay must be reported")
        assertFalse(unresolvedLine.contains("NSR:Quay:1"), "A resolved neighbour must not be reported as failed")
        assertFalse(unresolvedLine.contains("NSR:Quay:3"), "A resolved neighbour must not be reported as failed")
    }

    @Test
    fun `retryable only when a throttled quay is an endpoint of a missing pair`() {
        val coord = Coordinate(BigDecimal("60.0"), BigDecimal("10.0"))
        // A -> B -> C, all resolved, but A->B has no ServiceLink for some other reason. Quay 9
        // failed transiently but belongs to a stop that isn't in any missing pair, so retrying the
        // dataset would change nothing.
        val stopPoints =
            mapOf(
                "SSP:A" to StopPoint("SSP:A", null, coord, null, null),
                "SSP:B" to StopPoint("SSP:B", null, coord, null, null),
                "SSP:C" to StopPoint("SSP:C", null, coord, null, null),
            )
        val stopAssignments = mapOf("SSP:A" to "NSR:Quay:1", "SSP:B" to "NSR:Quay:2", "SSP:C" to "NSR:Quay:3")
        val unrelated =
            assertThrows(IncompleteJourneyPatternException::class.java) {
                NetexProcessor.checkJourneyPatternIntegrity(
                    jpStopSequences = mapOf("JP:1" to listOf("SSP:A", "SSP:B", "SSP:C")),
                    allServiceLinks = mapOf("SSP:B_SSP:C" to "SL:1"),
                    stopPoints = stopPoints,
                    stopAssignments = stopAssignments,
                    transientlyUnresolvedQuays = setOf("NSR:Quay:9"),
                )
            }
        assertFalse(unrelated.retryable, "A transient quay outside every missing pair must not force a rerun")

        // Now B itself is the throttled quay: it has a PSA, no coordinate, and both missing pairs
        // hinge on it.
        val throttled =
            assertThrows(IncompleteJourneyPatternException::class.java) {
                NetexProcessor.checkJourneyPatternIntegrity(
                    jpStopSequences = mapOf("JP:1" to listOf("SSP:A", "SSP:B", "SSP:C")),
                    allServiceLinks = emptyMap(),
                    stopPoints = stopPoints - "SSP:B",
                    stopAssignments = stopAssignments,
                    transientlyUnresolvedQuays = setOf("NSR:Quay:2"),
                )
            }
        assertTrue(throttled.retryable, "A throttled quay behind a missing pair must make the run retryable")
    }
}

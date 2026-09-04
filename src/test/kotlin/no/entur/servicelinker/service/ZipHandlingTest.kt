package no.entur.servicelinker.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.plugins.MetadataExtractor
import no.entur.servicelinker.util.PolylineEncoding
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipHandlingTest {
    private lateinit var mockOsrmService: OSRMService
    private lateinit var mockGcsService: GcsService
    private lateinit var mockNsrService: NsrService
    private lateinit var netexProcessor: NetexProcessor
    private lateinit var serviceLinkProducerService: ServiceLinkProducerService

    private val oslo = Coordinate(59.9139, 10.7522)
    private val lillestrøm = Coordinate(59.9556, 11.0464)

    @BeforeEach
    fun setup() {
        mockOsrmService = mockk(relaxed = true)
        mockGcsService = mockk(relaxed = true)
        mockNsrService = mockk(relaxed = true)
        netexProcessor = NetexProcessor(mockOsrmService, MetadataExtractor(), ServicelinkerMetrics(SimpleMeterRegistry()))
        serviceLinkProducerService =
            ServiceLinkProducerService(
                mockGcsService,
                netexProcessor,
                mockOsrmService,
                mockNsrService,
                MetadataExtractor(),
                "test-output",
            )

        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } returns
            OSRMRoute(
                distance = 15000.0,
                duration = 1200.0,
                geometry = PolylineEncoding.encode(listOf(oslo, lillestrøm)),
            )
    }

    private fun createTestZip(): ByteArray {
        val sampleXml = ClassPathResource("sample-netex.xml").inputStream.readBytes()
        val outputBuffer = ByteArrayOutputStream()

        ZipOutputStream(outputBuffer).use { zos ->
            zos.putNextEntry(ZipEntry("line1/netex.xml"))
            zos.write(sampleXml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("line2/netex.xml"))
            zos.write(sampleXml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("metadata.txt"))
            zos.write("some metadata".toByteArray())
            zos.closeEntry()
        }

        return outputBuffer.toByteArray()
    }

    @Test
    fun `should process ZIP archive with multiple XML files`() =
        runTest {
            val testZip = createTestZip()

            coEvery { mockGcsService.downloadFile("test-bucket", "test.zip") } returns testZip
            coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

            val addedCount = serviceLinkProducerService.processDataset("test-bucket", "test.zip")

            // Both XML files share the same stop pair — deduplication produces 1 unique ServiceLink globally
            assertEquals(1, addedCount, "Should have added 1 deduplicated service link across ZIP files")
        }

    @Test
    fun `should preserve non-XML files in ZIP archive`() =
        runTest {
            val testZip = createTestZip()

            var uploadedData: ByteArray? = null
            coEvery { mockGcsService.downloadFile("test-bucket", "test.zip") } returns testZip
            coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } answers {
                uploadedData = firstArg()
            }

            serviceLinkProducerService.processDataset("test-bucket", "test.zip")

            // Verify the output ZIP contains all entries
            assertNotNull(uploadedData, "Uploaded data should not be null")
            val entries = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(requireNotNull(uploadedData))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entries.add(entry.name)
                    entry = zis.nextEntry
                }
            }

            assertTrue(entries.contains("metadata.txt"), "Should preserve non-XML files")
            assertTrue(entries.contains("line1/netex.xml"), "Should contain processed XML files")
            assertTrue(entries.contains("line2/netex.xml"), "Should contain processed XML files")
            assertEquals(3, entries.size, "Should have same number of entries")
        }

    @Test
    fun `should process single XML file via processDataset`() =
        runTest {
            val sampleXml = ClassPathResource("sample-netex.xml").inputStream.readBytes()

            coEvery { mockGcsService.downloadFile("test-bucket", "test.xml") } returns sampleXml
            coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

            val addedCount = serviceLinkProducerService.processDataset("test-bucket", "test.xml")

            assertTrue(addedCount > 0, "Should have added service links from single XML")
        }

    @Test
    fun `empty modesFilter short-circuits processing, uploads original data, and returns zero`() =
        runTest {
            val testZip = createTestZip()

            var uploadedData: ByteArray? = null
            coEvery { mockGcsService.downloadFile("test-bucket", "test.zip") } returns testZip
            coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } answers {
                uploadedData = firstArg()
            }

            val addedCount = serviceLinkProducerService.processDataset("test-bucket", "test.zip", emptySet())

            assertEquals(0, addedCount, "Empty modesFilter should produce zero service links")
            assertNotNull(uploadedData, "Original data should still be uploaded")
            assertEquals(testZip.size, requireNotNull(uploadedData).size, "Uploaded data should be the original unmodified ZIP")
            coVerify(exactly = 0) { mockOsrmService.calculateRoute(any(), any(), any()) }
        }

    /**
     * Regression test for: ServiceLinks written to line file instead of shared data file
     * when ScheduledStopPoint elements have no inline coordinates (coordinates resolved via NSR).
     *
     * The NeTEx identity constraint ScheduledStopPoint_KeyRef requires that every
     * FromPointRef/ToPointRef in a ServiceLink resolves to a ScheduledStopPoint in the
     * same PublicationDelivery. When stops are declared in _shared_data.xml without
     * coordinates, and NSR enrichment is used, the stopDeclarationFile map must still
     * correctly point to _shared_data.xml so ServiceLinks are written there.
     */
    @Test
    fun `ServiceLinks should be written to the file that declares the ScheduledStopPoints even when coordinates come from NSR`() =
        runTest {
            // Use timestamp-based versions (as seen in real NWY data) to verify version propagation
            val stopVersion = "20260212093839"

            val sharedDataXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1">
                      <frames>
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
                            <PassengerStopAssignment version="$stopVersion" id="TST:PassengerStopAssignment:A" order="1">
                              <ScheduledStopPointRef ref="TST:ScheduledStopPoint:A" version="$stopVersion"/>
                              <QuayRef ref="NSR:Quay:1" version="1"/>
                            </PassengerStopAssignment>
                            <PassengerStopAssignment version="$stopVersion" id="TST:PassengerStopAssignment:B" order="2">
                              <ScheduledStopPointRef ref="TST:ScheduledStopPoint:B" version="$stopVersion"/>
                              <QuayRef ref="NSR:Quay:2" version="1"/>
                            </PassengerStopAssignment>
                          </stopAssignments>
                        </ServiceFrame>
                      </frames>
                    </CompositeFrame>
                  </dataObjects>
                </PublicationDelivery>
                """.trimIndent().toByteArray()

            val lineXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:2" version="1">
                      <frames>
                        <ServiceFrame id="TST:ServiceFrame:2" version="1">
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
                """.trimIndent().toByteArray()

            // NSR enrichment returns coordinates for stops that have no inline coordinates
            coEvery {
                mockNsrService.lookupQuayCoordinates(any<Collection<String>>())
            } returns NsrLookupResult(mapOf("NSR:Quay:1" to oslo, "NSR:Quay:2" to lillestrøm), emptySet())

            // Line file is first — matches real-world ordering (e.g. Havila ZIP).
            // The fallback `firstXmlFile` would be "line.xml", so if stopDeclarationFile
            // is not populated correctly the ServiceLinks would end up in the wrong file.
            val zipData =
                ByteArrayOutputStream()
                    .also { buf ->
                        ZipOutputStream(buf).use { zos ->
                            zos.putNextEntry(ZipEntry("line.xml"))
                            zos.write(lineXml)
                            zos.closeEntry()
                            zos.putNextEntry(ZipEntry("_shared_data.xml"))
                            zos.write(sharedDataXml)
                            zos.closeEntry()
                        }
                    }.toByteArray()

            val (processedZip, addedCount) = serviceLinkProducerService.processZipDirectly(zipData)

            assertEquals(1, addedCount, "Should have created exactly one ServiceLink")

            // Extract each file from the output ZIP and check ServiceLink placement
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val outputFiles = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(processedZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    outputFiles[entry.name] = zis.readBytes()
                    entry = zis.nextEntry
                }
            }

            val sharedDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(outputFiles.getValue("_shared_data.xml")))
            val lineDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(outputFiles.getValue("line.xml")))

            val sharedDataServiceLinks = sharedDoc.getElementsByTagName("ServiceLink").length
            val lineServiceLinks = lineDoc.getElementsByTagName("ServiceLink").length
            val lineServiceLinkInJPs = lineDoc.getElementsByTagName("ServiceLinkInJourneyPattern").length

            assertEquals(
                1,
                sharedDataServiceLinks,
                "ServiceLink must be in _shared_data.xml (where ScheduledStopPoints are declared)",
            )
            assertEquals(
                0,
                lineServiceLinks,
                "ServiceLink must NOT be in line.xml (ScheduledStopPoint_KeyRef constraint would be violated)",
            )
            assertEquals(
                1,
                lineServiceLinkInJPs,
                "linksInSequence must be written to the JP file even for cross-file ServiceLinks; " +
                    "omitting version on ServiceLinkRef prevents ServiceLink_KeyRef from firing",
            )

            // Verify FromPointRef/ToPointRef use the ScheduledStopPoint's actual version,
            // not hardcoded "1". This is critical for NSR-enriched stops where the version
            // comes from the ScheduledStopPoint declaration, not inline coordinates.
            val serviceLink = sharedDoc.getElementsByTagName("ServiceLink").item(0) as org.w3c.dom.Element
            val fromPointRef = serviceLink.getElementsByTagName("FromPointRef").item(0) as org.w3c.dom.Element
            val toPointRef = serviceLink.getElementsByTagName("ToPointRef").item(0) as org.w3c.dom.Element
            assertEquals(
                stopVersion,
                fromPointRef.getAttribute("version"),
                "FromPointRef must use the ScheduledStopPoint's actual version for NSR-enriched stops",
            )
            assertEquals(
                stopVersion,
                toPointRef.getAttribute("version"),
                "ToPointRef must use the ScheduledStopPoint's actual version for NSR-enriched stops",
            )
        }

    /**
     * Regression test for: Missing ScheduledStopPoint key reference when ServiceLink
     * references stops declared in different files.
     *
     * When from-stop is in _shared_data.xml but to-stop is only in the line file (or
     * vice versa), the ServiceLink ends up in one file where the other stop isn't
     * declared, violating NeTEx ScheduledStopPoint_KeyRef. The fix injects a minimal
     * stub ScheduledStopPoint declaration for any missing referenced stops.
     */
    @Test
    fun `should inject stub ScheduledStopPoint when ServiceLink references stops in different files`() =
        runTest {
            // shared_data declares stop A only
            val sharedDataXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:1" version="1">
                      <frames>
                        <ServiceFrame id="TST:ServiceFrame:1" version="1">
                          <scheduledStopPoints>
                            <ScheduledStopPoint version="1" id="TST:ScheduledStopPoint:A">
                              <Name>Stop A</Name>
                              <Location><Longitude>10.7522</Longitude><Latitude>59.9139</Latitude></Location>
                            </ScheduledStopPoint>
                          </scheduledStopPoints>
                        </ServiceFrame>
                      </frames>
                    </CompositeFrame>
                  </dataObjects>
                </PublicationDelivery>
                """.trimIndent().toByteArray()

            // line file declares stop B and has the JourneyPattern A→B
            val lineXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" version="1.0">
                  <dataObjects>
                    <CompositeFrame id="TST:CompositeFrame:2" version="1">
                      <frames>
                        <ServiceFrame id="TST:ServiceFrame:2" version="1">
                          <scheduledStopPoints>
                            <ScheduledStopPoint version="1" id="TST:ScheduledStopPoint:B">
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

            val (processedZip, addedCount) = serviceLinkProducerService.processZipDirectly(zipData)
            assertEquals(1, addedCount)

            // Parse output files
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val outputFiles = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(processedZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    outputFiles[entry.name] = zis.readBytes()
                    entry = zis.nextEntry
                }
            }

            // The ServiceLink should be placed in _shared_data.xml (from-stop A is declared there)
            val sharedDoc = factory.newDocumentBuilder().parse(ByteArrayInputStream(outputFiles.getValue("_shared_data.xml")))
            val serviceLinks = sharedDoc.getElementsByTagName("ServiceLink")
            assertEquals(1, serviceLinks.length, "ServiceLink should be in _shared_data.xml")

            // Stop B is only declared in line.xml, so a stub must have been added to _shared_data.xml
            val declaredStops = sharedDoc.getElementsByTagName("ScheduledStopPoint")
            val declaredIds =
                (0 until declaredStops.length)
                    .map {
                        (declaredStops.item(it) as org.w3c.dom.Element).getAttribute("id")
                    }.toSet()
            assertTrue(declaredIds.contains("TST:ScheduledStopPoint:A"), "Original stop A should be declared")
            assertTrue(
                declaredIds.contains("TST:ScheduledStopPoint:B"),
                "Stub for stop B should be injected so ScheduledStopPoint_KeyRef constraint is satisfied",
            )
        }

    @Test
    fun `should detect ZIP by magic bytes regardless of extension`() =
        runTest {
            val testZip = createTestZip()

            coEvery { mockGcsService.downloadFile("test-bucket", "dataset.netex") } returns testZip
            coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

            val addedCount = serviceLinkProducerService.processDataset("test-bucket", "dataset.netex")

            assertTrue(addedCount > 0, "Should detect ZIP by magic bytes and process XML files inside")
        }
}

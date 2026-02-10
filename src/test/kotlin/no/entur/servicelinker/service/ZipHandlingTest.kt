package no.entur.servicelinker.service

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMRoute
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipHandlingTest {

    private lateinit var mockOsrmService: OSRMService
    private lateinit var mockGcsService: GcsService
    private lateinit var netexProcessor: NetexProcessor
    private lateinit var serviceLinkProducerService: ServiceLinkProducerService

    private val oslo = Coordinate(59.9139, 10.7522)
    private val lillestrøm = Coordinate(59.9556, 11.0464)

    @BeforeEach
    fun setup() {
        mockOsrmService = mockk(relaxed = true)
        mockGcsService = mockk(relaxed = true)
        netexProcessor = NetexProcessor(mockOsrmService)
        serviceLinkProducerService = ServiceLinkProducerService(
            mockGcsService, netexProcessor, mockOsrmService, "test-output"
        )

        coEvery {
            mockOsrmService.calculateRoute(any(), any(), any())
        } returns OSRMRoute(
            distance = 15000.0,
            duration = 1200.0,
            geometry = PolylineEncoding.encode(listOf(oslo, lillestrøm))
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
    fun `should process ZIP archive with multiple XML files`() = runTest {
        val testZip = createTestZip()

        coEvery { mockGcsService.downloadFile("test-bucket", "test.zip") } returns testZip
        coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

        val addedCount = serviceLinkProducerService.processDataset("test-bucket", "test.zip")

        // Each XML file has 3 stop points → 3 pairs, so 3 * 2 files = 6
        assertTrue(addedCount > 0, "Should have added service links from ZIP contents")
    }

    @Test
    fun `should preserve non-XML files in ZIP archive`() = runTest {
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
    fun `should process single XML file via processDataset`() = runTest {
        val sampleXml = ClassPathResource("sample-netex.xml").inputStream.readBytes()

        coEvery { mockGcsService.downloadFile("test-bucket", "test.xml") } returns sampleXml
        coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

        val addedCount = serviceLinkProducerService.processDataset("test-bucket", "test.xml")

        assertTrue(addedCount > 0, "Should have added service links from single XML")
    }

    @Test
    fun `should detect ZIP by magic bytes regardless of extension`() = runTest {
        val testZip = createTestZip()

        coEvery { mockGcsService.downloadFile("test-bucket", "dataset.netex") } returns testZip
        coEvery { mockGcsService.uploadFile(any<ByteArray>(), any<String>(), any<String>(), any()) } returns Unit

        val addedCount = serviceLinkProducerService.processDataset("test-bucket", "dataset.netex")

        assertTrue(addedCount > 0, "Should detect ZIP by magic bytes and process XML files inside")
    }
}

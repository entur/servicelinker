package no.entur.servicelinker.controller

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.model.ProcessedDataset
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ServiceLinkControllerTest {
    private lateinit var mockService: ServiceLinkProducerService
    private lateinit var controller: ServiceLinkController

    @BeforeEach
    fun setup() {
        mockService = mockk(relaxed = true)
        controller = ServiceLinkController(mockService)
    }

    // --- process-xml ---

    @Test
    fun `process-xml with no modes passes null filter`() =
        runTest {
            coEvery { mockService.processXmlContentDirectly(any(), any(), null) } returns
                DirectProcessingResult(true, "<xml/>", 1)

            controller.processXmlFile("<xml/>", "test.xml", null)

            coVerify { mockService.processXmlContentDirectly("<xml/>", "test.xml", null) }
        }

    @Test
    fun `process-xml with bracket notation passes uppercase set`() =
        runTest {
            coEvery { mockService.processXmlContentDirectly(any(), any(), setOf("BUS", "TRAM")) } returns
                DirectProcessingResult(true, "<xml/>", 1)

            controller.processXmlFile("<xml/>", "test.xml", "[BUS,TRAM]")

            coVerify { mockService.processXmlContentDirectly("<xml/>", "test.xml", setOf("BUS", "TRAM")) }
        }

    @Test
    fun `process-xml with plain comma-separated modes passes uppercase set`() =
        runTest {
            coEvery { mockService.processXmlContentDirectly(any(), any(), setOf("BUS", "RAIL")) } returns
                DirectProcessingResult(true, "<xml/>", 1)

            controller.processXmlFile("<xml/>", "test.xml", "bus,RAIL")

            coVerify { mockService.processXmlContentDirectly("<xml/>", "test.xml", setOf("BUS", "RAIL")) }
        }

    @Test
    fun `process-xml with empty bracket notation passes empty set (process none)`() =
        runTest {
            coEvery { mockService.processXmlContentDirectly(any(), any(), emptySet()) } returns
                DirectProcessingResult(true, "<xml/>", 0)

            controller.processXmlFile("<xml/>", "test.xml", "[]")

            coVerify { mockService.processXmlContentDirectly("<xml/>", "test.xml", emptySet()) }
        }

    // --- process-zip ---

    @Test
    fun `process-zip with no modes passes null filter`() =
        runTest {
            val zip = byteArrayOf(0x50, 0x4B)
            coEvery { mockService.processZipDirectly(any(), null) } returns ProcessedDataset(zip, 0)

            controller.processZipFile(zip, "test.zip", null)

            coVerify { mockService.processZipDirectly(zip, null) }
        }

    @Test
    fun `process-zip with bracket notation passes uppercase set`() =
        runTest {
            val zip = byteArrayOf(0x50, 0x4B)
            coEvery { mockService.processZipDirectly(any(), setOf("FERRY")) } returns ProcessedDataset(zip, 3)

            controller.processZipFile(zip, "test.zip", "[ferry]")

            coVerify { mockService.processZipDirectly(zip, setOf("FERRY")) }
        }

    @Test
    fun `process-zip with empty bracket notation passes empty set (process none)`() =
        runTest {
            val zip = byteArrayOf(0x50, 0x4B)
            coEvery { mockService.processZipDirectly(any(), emptySet()) } returns ProcessedDataset(zip, 0)

            controller.processZipFile(zip, "test.zip", "[]")

            coVerify { mockService.processZipDirectly(zip, emptySet()) }
        }

    @Test
    fun `process-zip returns 200 with processed bytes on success`() =
        runTest {
            val input = byteArrayOf(0x50, 0x4B)
            val output = byteArrayOf(0x50, 0x4B, 0x01)
            coEvery { mockService.processZipDirectly(any(), any()) } returns ProcessedDataset(output, 2)

            val response = controller.processZipFile(input, "test.zip", null)

            assertEquals(200, response.statusCode.value())
            assertEquals(output, response.body)
        }
}

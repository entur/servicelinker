package no.entur.servicelinker.controller

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ServiceLinkControllerHttpTest {

    private lateinit var mockService: ServiceLinkProducerService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockService = mockk(relaxed = true)
        mockMvc = MockMvcBuilders.standaloneSetup(ServiceLinkController(mockService)).build()
    }

    @Test
    fun `bracket notation modes=BUS,TRAM is parsed into setOf BUS, TRAM`() {
        coEvery { mockService.processXmlContentDirectly(any(), any(), setOf("BUS", "TRAM")) } returns
                DirectProcessingResult(true, "<result/>", 1)

        mockMvc.post("/api/servicelink/process-xml") {
            param("modes", "[BUS,TRAM]")
            contentType = MediaType.parseMediaType("application/xml")
            content = "<xml/>"
        }.andExpect { status { isOk() } }

        coVerify { mockService.processXmlContentDirectly(any(), any(), setOf("BUS", "TRAM")) }
    }

    @Test
    fun `bracket notation modes=empty is parsed into emptySet`() {
        coEvery { mockService.processXmlContentDirectly(any(), any(), emptySet()) } returns
                DirectProcessingResult(true, "<result/>", 0)

        mockMvc.post("/api/servicelink/process-xml") {
            param("modes", "[]")
            contentType = MediaType.parseMediaType("application/xml")
            content = "<xml/>"
        }.andExpect { status { isOk() } }

        coVerify { mockService.processXmlContentDirectly(any(), any(), emptySet()) }
    }

    @Test
    fun `no modes param passes null`() {
        coEvery { mockService.processXmlContentDirectly(any(), any(), null) } returns
                DirectProcessingResult(true, "<result/>", 0)

        mockMvc.post("/api/servicelink/process-xml") {
            contentType = MediaType.parseMediaType("application/xml")
            content = "<xml/>"
        }.andExpect { status { isOk() } }

        coVerify { mockService.processXmlContentDirectly(any(), any(), null) }
    }
}

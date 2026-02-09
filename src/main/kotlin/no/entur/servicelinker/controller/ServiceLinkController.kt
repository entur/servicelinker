package no.entur.servicelinker.controller

import no.entur.servicelinker.service.ServiceLinkProducerService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/servicelink")
class ServiceLinkController(
    private val serviceLinkProducerService: ServiceLinkProducerService
) {
    private val logger = LoggerFactory.getLogger(ServiceLinkController::class.java)

    @PostMapping("/process-xml", consumes = ["application/xml", "text/xml"], produces = ["application/xml"])
    suspend fun processXmlFile(
        @RequestBody xmlContent: String,
        @RequestParam(required = false) filename: String = "manual_upload_${System.currentTimeMillis()}.xml"
    ): ResponseEntity<String> {
        logger.info("Manual XML processing request for file: $filename")

        val result = serviceLinkProducerService.processXmlContentDirectly(xmlContent, filename)

        return if (result.success) {
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"processed_$filename\"")
                .body(result.processedXml)
        } else {
            ResponseEntity.badRequest()
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error>${result.errorMessage}</error>")
        }
    }

    @GetMapping("/cache/stats")
    fun getCacheStatistics(): ResponseEntity<Map<String, Any>> {
        val stats = serviceLinkProducerService.getRoutingCacheStatistics()
        return ResponseEntity.ok(stats)
    }

    @PostMapping("/cache/clear")
    fun clearCache(): ResponseEntity<Map<String, String>> {
        serviceLinkProducerService.clearRoutingCache()
        return ResponseEntity.ok(mapOf("message" to "Cache cleared successfully"))
    }
}

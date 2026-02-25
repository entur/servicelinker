package no.entur.servicelinker.controller

import no.entur.servicelinker.service.ServiceLinkProducerService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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
        @RequestParam(required = false) filename: String = "manual_upload_${System.currentTimeMillis()}.xml",
        @RequestParam(required = false) modes: String? = null
    ): ResponseEntity<String> {
        logger.info("Manual XML processing request for file: $filename, modes: $modes")

        val result = serviceLinkProducerService.processXmlContentDirectly(xmlContent, filename, parseModes(modes))

        return if (result.success) {
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"processed_$filename\"")
                .body(result.processedXml)
        } else {
            ResponseEntity.badRequest()
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error>${result.errorMessage}</error>")
        }
    }

    @PostMapping("/process-zip", consumes = ["application/zip", "application/octet-stream"])
    suspend fun processZipFile(
        @RequestBody zipData: ByteArray,
        @RequestParam(required = false) filename: String = "manual_upload_${System.currentTimeMillis()}.zip",
        @RequestParam(required = false) modes: String? = null
    ): ResponseEntity<ByteArray> {
        logger.info("Manual ZIP processing request for file: $filename, modes: $modes")

        return try {
            val (processedZip, addedCount) = serviceLinkProducerService.processZipDirectly(zipData, parseModes(modes))
            logger.info("Successfully processed ZIP. Added $addedCount ServiceLinks for file: $filename")
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"processed_$filename\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(processedZip)
        } catch (e: Exception) {
            logger.error("Error processing ZIP directly: $filename", e)
            ResponseEntity.badRequest().build()
        }
    }

    private fun parseModes(modes: String?): Set<String>? =
        modes?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().uppercase() }?.toSet()

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

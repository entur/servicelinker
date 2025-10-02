package no.entur.servicelinker.controller

import mu.KotlinLogging
import no.entur.servicelinker.model.ProcessingRequest
import no.entur.servicelinker.model.ProcessingResult
import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.service.ServicelinkerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/servicelink")
class ServiceLinkController(
    private val servicelinkerService: ServicelinkerService
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/process")
    fun processFile(@RequestBody request: ProcessingRequest): ResponseEntity<ProcessingResult> {
        logger.info { "Manual processing request for file: ${request.gcsFilePath}" }

        val result = servicelinkerService.processNetexFile(request)

        return if (result.success) {
            ResponseEntity.ok(result)
        } else {
            ResponseEntity.badRequest().body(result)
        }
    }

    @PostMapping("/process-xml", consumes = ["application/xml", "text/xml"], produces = ["application/xml"])
    suspend fun processXmlFile(
        @RequestBody xmlContent: String,
        @RequestParam(required = false) filename: String = "manual_upload_${System.currentTimeMillis()}.xml"
    ): ResponseEntity<String> {
        logger.info { "Manual XML processing request for file: $filename" }

        val result = servicelinkerService.processXmlContentDirectly(xmlContent, filename)

        return if (result.success) {
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"processed_$filename\"")
                .body(result.processedXml)
        } else {
            ResponseEntity.badRequest()
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error>${result.errorMessage}</error>")
        }
    }

    @PostMapping("/process-xml-multipart", consumes = ["multipart/form-data"], produces = ["application/xml"])
    suspend fun processXmlFileUpload(
        @RequestParam("file") file: org.springframework.web.multipart.MultipartFile
    ): ResponseEntity<String> {
        logger.info { "Manual XML file upload processing request for file: ${file.originalFilename}" }

        if (file.isEmpty) {
            return ResponseEntity.badRequest()
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error>Uploaded file is empty</error>")
        }

        val xmlContent = try {
            String(file.bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return ResponseEntity.badRequest()
                .body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><error>Failed to read uploaded file: ${e.message}</error>")
        }

        val filename = file.originalFilename ?: "upload_${System.currentTimeMillis()}.xml"
        val result = servicelinkerService.processXmlContentDirectly(xmlContent, filename)

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
        val stats = servicelinkerService.getRoutingCacheStatistics()
        return ResponseEntity.ok(stats)
    }

    @PostMapping("/cache/clear")
    fun clearCache(): ResponseEntity<Map<String, String>> {
        servicelinkerService.clearRoutingCache()
        return ResponseEntity.ok(mapOf("message" to "Cache cleared successfully"))
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "UP",
            "service" to "ServiceLink Producer",
            "timestamp" to System.currentTimeMillis().toString()
        ))
    }
}

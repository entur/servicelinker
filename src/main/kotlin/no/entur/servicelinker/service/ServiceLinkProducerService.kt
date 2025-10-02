package no.entur.servicelinker.service

import no.entur.servicelinker.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ServiceLinkProducerService(
    private val gcsService: GcsService,
    private val netexProcessor: NetexProcessor,
    private val osrmService: OSRMService
) {
    private val logger = LoggerFactory.getLogger(ServiceLinkProducerService::class.java)

    suspend fun processNetexFile(request: ProcessingRequest): ProcessingResult {
        return try {
            logger.info("Starting ServiceLink production for file: ${request.gcsFilePath}")

            if (!gcsService.fileExists(request.gcsFilePath)) {
                return ProcessingResult(
                    success = false,
                    outputFilePath = null,
                    addedServiceLinksCount = 0,
                    errorMessage = "Input file not found: ${request.gcsFilePath}"
                )
            }

            val inputStream = gcsService.downloadFile(request.gcsFilePath)
                ?: return ProcessingResult(
                    success = false,
                    outputFilePath = null,
                    addedServiceLinksCount = 0,
                    errorMessage = "Failed to download file: ${request.gcsFilePath}"
                )

            inputStream.use { stream ->
                val (processedXml, addedCount) = netexProcessor.processNetexFile(stream)

                if (addedCount == 0) {
                    logger.info("No ServiceLinks needed to be added for file: ${request.gcsFilePath}")
                    return ProcessingResult(
                        success = true,
                        outputFilePath = request.gcsFilePath, // Return original file path
                        addedServiceLinksCount = 0
                    )
                }

                val outputPath = gcsService.uploadFile(
                    processedXml,
                    request.gcsFilePath,
                    request.outputBucket
                )

                if (outputPath != null) {
                    logger.info("Successfully processed file. Added $addedCount ServiceLinks. Output: $outputPath")
                    ProcessingResult(
                        success = true,
                        outputFilePath = outputPath,
                        addedServiceLinksCount = addedCount
                    )
                } else {
                    ProcessingResult(
                        success = false,
                        outputFilePath = null,
                        addedServiceLinksCount = addedCount,
                        errorMessage = "Failed to upload processed file"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing NeTEx file: ${request.gcsFilePath}", e)
            ProcessingResult(
                success = false,
                outputFilePath = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }

    suspend fun processXmlContentDirectly(xmlContent: String, filename: String): DirectProcessingResult {
        return try {
            logger.info("Starting direct ServiceLink production for XML content: $filename")
            val inputStream = xmlContent.byteInputStream(Charsets.UTF_8)
            inputStream.use { stream ->
                val (processedXml, addedCount) = netexProcessor.processNetexFileWithRoutes(stream)
                logger.info("Successfully processed XML content. Added $addedCount ServiceLinks for file: $filename")
                DirectProcessingResult(
                    success = true,
                    processedXml = String(processedXml, Charsets.UTF_8),
                    addedServiceLinksCount = addedCount
                )
            }
        } catch (e: Exception) {
            logger.error("Error processing XML content directly: $filename", e)
            DirectProcessingResult(
                success = false,
                processedXml = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }
    fun getRoutingCacheStatistics(): Map<String, Any> {
        return mapOf(
            "cacheSize" to osrmService.getCacheSize(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun clearRoutingCache() {
        osrmService.clearCache()
        logger.info("Routing cache cleared")
    }
}

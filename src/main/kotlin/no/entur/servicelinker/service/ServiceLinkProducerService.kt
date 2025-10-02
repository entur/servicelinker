package no.entur.servicelinker.service

import mu.KotlinLogging
import no.entur.servicelinker.model.*
import org.springframework.stereotype.Service

@Service
class ServicelinkerService(
    private val gcsService: GcsService,
    private val netexProcessor: NetexProcessor,
    private val osrmService: OSRMService
) {
    private val logger = KotlinLogging.logger {}

    fun processNetexFile(request: ProcessingRequest): ProcessingResult {
        return try {
            logger.info { "Starting ServiceLink production for file: ${request.gcsFilePath}" }

            // Validate input file exists
            if (!gcsService.fileExists(request.gcsFilePath)) {
                return ProcessingResult(
                    success = false,
                    outputFilePath = null,
                    addedServiceLinksCount = 0,
                    errorMessage = "Input file not found: ${request.gcsFilePath}"
                )
            }

            // Download and process the NeTEx file
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
                    logger.info { "No ServiceLinks needed to be added for file: ${request.gcsFilePath}" }
                    return ProcessingResult(
                        success = true,
                        outputFilePath = request.gcsFilePath, // Return original file path
                        addedServiceLinksCount = 0
                    )
                }

                // Upload the processed file
                val outputPath = gcsService.uploadFile(
                    processedXml,
                    request.gcsFilePath,
                    request.outputBucket
                )

                if (outputPath != null) {
                    logger.info { "Successfully processed file. Added $addedCount ServiceLinks. Output: $outputPath" }
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
            logger.error(e) { "Error processing NeTEx file: ${request.gcsFilePath}" }
            ProcessingResult(
                success = false,
                outputFilePath = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }

    fun processXmlContent(xmlContent: String, filename: String, outputBucket: String? = null): ProcessingResult {
        return try {
            logger.info { "Starting ServiceLink production for XML content: $filename" }

            // Process the XML content directly
            val inputStream = xmlContent.byteInputStream(Charsets.UTF_8)

            inputStream.use { stream ->
                val (processedXml, addedCount) = netexProcessor.processNetexFile(stream)

                if (addedCount == 0) {
                    logger.info { "No ServiceLinks needed to be added for file: $filename" }
                    return ProcessingResult(
                        success = true,
                        outputFilePath = null, // No file was uploaded since no changes were made
                        addedServiceLinksCount = 0
                    )
                }

                // Upload the processed file to GCS
                val outputPath = gcsService.uploadProcessedXml(
                    processedXml,
                    filename,
                    outputBucket
                )

                if (outputPath != null) {
                    logger.info { "Successfully processed XML content. Added $addedCount ServiceLinks. Output: $outputPath" }
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
            logger.error(e) { "Error processing XML content: $filename" }
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
            logger.info { "Starting direct ServiceLink production for XML content: $filename" }
            val inputStream = xmlContent.byteInputStream(Charsets.UTF_8)
            inputStream.use { stream ->
                val (processedXml, addedCount) = netexProcessor.processNetexFileWithRoutes(stream)
                logger.info { "Successfully processed XML content. Added $addedCount ServiceLinks for file: $filename" }
                DirectProcessingResult(
                    success = true,
                    processedXml = String(processedXml, Charsets.UTF_8),
                    addedServiceLinksCount = addedCount
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing XML content directly: $filename" }
            DirectProcessingResult(
                success = false,
                processedXml = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }

    suspend fun enhanceServiceLinksWithRouting(stationPairs: List<StationPair>): List<ServiceLink> {
        val serviceLinks = mutableListOf<ServiceLink>()

        for (pair in stationPairs) {
            try {
                val route = osrmService.calculateRoute(
                    pair.from.coordinate,
                    pair.to.coordinate,
                    pair.from.transportMode ?: pair.to.transportMode
                )

                val coordinates = route?.geometry?.coordinates?.map { coord ->
                    Coordinate(coord[1], coord[0]) // OSRM returns [lon, lat], we need [lat, lon]
                } ?: emptyList()

                val serviceLink = ServiceLink(
                    id = pair.generateServiceLinkId("SL_"),
                    fromStopPointRef = pair.from.id,
                    toStopPointRef = pair.to.id,
                    distance = route?.distance?.toInt(),
                    linkSequenceProjection = if (coordinates.isNotEmpty()) {
                        LinkSequenceProjection(coordinates)
                    } else null
                )

                serviceLinks.add(serviceLink)

                logger.debug { "Created ServiceLink ${serviceLink.id} with ${coordinates.size} coordinates" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to enhance ServiceLink for pair ${pair.from.id} -> ${pair.to.id}" }

                // Create basic ServiceLink without routing data
                val serviceLink = ServiceLink(
                    id = pair.generateServiceLinkId("SL_"),
                    fromStopPointRef = pair.from.id,
                    toStopPointRef = pair.to.id,
                    distance = null,
                    linkSequenceProjection = null
                )
                serviceLinks.add(serviceLink)
            }
        }

        return serviceLinks
    }

    fun getRoutingCacheStatistics(): Map<String, Any> {
        return mapOf(
            "cacheSize" to osrmService.getCacheSize(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun clearRoutingCache() {
        osrmService.clearCache()
        logger.info { "Routing cache cleared" }
    }
}

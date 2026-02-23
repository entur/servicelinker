package no.entur.servicelinker.service

import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.model.StopPoint
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Service
class ServiceLinkProducerService(
    private val gcsService: GcsService,
    private val netexProcessor: NetexProcessor,
    private val osrmService: OSRMService,
    private val nsrService: NsrService,
    @Value("\${gcs.bucket.output}") private val outputBucket: String
) {
    private val logger = LoggerFactory.getLogger(ServiceLinkProducerService::class.java)

    suspend fun processDataset(inputBucket: String, objectName: String, modesFilter: Set<String>? = null): Int {
        logger.info("Processing dataset: gs://$inputBucket/$objectName, modesFilter=$modesFilter")

        val data = gcsService.downloadFile(inputBucket, objectName)

        val isZip = data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
                && data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

        if (modesFilter != null && modesFilter.isEmpty()) {
            logger.info("ServiceLinkModes is empty — skipping service link generation for $objectName")
            gcsService.uploadFile(data, outputBucket, objectName, if (isZip) "application/zip" else "application/xml")
            return 0
        }

        val (processedData, totalAdded) = if (isZip || objectName.endsWith(".zip", ignoreCase = true)) {
            processZipArchive(data, modesFilter)
        } else {
            processSingleXml(data, modesFilter)
        }

        gcsService.uploadFile(
            processedData,
            outputBucket,
            objectName,
            if (isZip) "application/zip" else "application/xml"
        )

        logger.info("Dataset processing complete. Added $totalAdded ServiceLinks total. Output: gs://$outputBucket/$objectName")
        return totalAdded
    }

    suspend fun processZipDirectly(zipData: ByteArray): Pair<ByteArray, Int> = processZipArchive(zipData)

    private suspend fun processZipArchive(zipData: ByteArray, modesFilter: Set<String>? = null): Pair<ByteArray, Int> {
        // First pass: collect stop points and stop assignments (lightweight — only keeps extracted data, not raw XML)
        val allStopPoints = mutableMapOf<String, StopPoint>()
        val allStopAssignments = mutableMapOf<String, String>()

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                    try {
                        val entryBytes = zis.readBytes()
                        val document = netexProcessor.parseXmlDocument(ByteArrayInputStream(entryBytes))
                        val stops = netexProcessor.extractStopPoints(document).associateBy { it.id }
                        val assignments = netexProcessor.extractStopAssignments(document)
                        allStopPoints.putAll(stops)
                        allStopAssignments.putAll(assignments)
                    } catch (e: Exception) {
                        logger.warn("First pass failed for ${entry.name}: ${e.message}")
                    }
                }
                entry = zis.nextEntry
            }
        }

        // Enrich: resolve coordinates via NSR only for stops that have an assignment but no coordinates
        val enrichedStopPoints = enrichStopPointsViaNsr(allStopPoints, allStopAssignments)

        logger.info("ZIP first pass: ${allStopPoints.size} stops with coordinates, " +
                "${enrichedStopPoints.size - allStopPoints.size} enriched via NSR, " +
                "${allStopAssignments.size} stop assignments")

        // Second pass: re-read ZIP from original bytes, process each entry with enriched stop map
        val outputBuffer = ByteArrayOutputStream()
        var totalAdded = 0

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            ZipOutputStream(outputBuffer).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryData = zis.readBytes()

                    if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                        logger.info("Processing XML entry: ${entry.name}")
                        val (processedXml, addedCount) = netexProcessor.processNetexFile(
                            ByteArrayInputStream(entryData),
                            enrichedStopPoints,
                            modesFilter
                        )
                        totalAdded += addedCount

                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.write(processedXml)
                    } else {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.write(entryData)
                    }
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        return Pair(outputBuffer.toByteArray(), totalAdded)
    }

    private suspend fun processSingleXml(xmlData: ByteArray, modesFilter: Set<String>? = null): Pair<ByteArray, Int> {
        val enrichedStops = extractAndEnrichStops(xmlData)
        return netexProcessor.processNetexFile(ByteArrayInputStream(xmlData), enrichedStops, modesFilter)
    }

    suspend fun processXmlContentDirectly(xmlContent: String, filename: String): DirectProcessingResult {
        return try {
            logger.info("Starting direct ServiceLink production for XML content: $filename")
            val xmlData = xmlContent.toByteArray(Charsets.UTF_8)
            val enrichedStops = extractAndEnrichStops(xmlData)
            val (processedXml, addedCount) = netexProcessor.processNetexFile(
                ByteArrayInputStream(xmlData),
                enrichedStops
            )
            logger.info("Successfully processed XML content. Added $addedCount ServiceLinks for file: $filename")
            DirectProcessingResult(
                success = true,
                processedXml = String(processedXml, Charsets.UTF_8),
                addedServiceLinksCount = addedCount
            )
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

    private suspend fun extractAndEnrichStops(xmlData: ByteArray): Map<String, StopPoint> {
        return try {
            val document = netexProcessor.parseXmlDocument(ByteArrayInputStream(xmlData))
            val stops = netexProcessor.extractStopPoints(document).associateBy { it.id }
            val assignments = netexProcessor.extractStopAssignments(document)
            enrichStopPointsViaNsr(stops, assignments)
        } catch (e: Exception) {
            logger.warn("Failed to extract/enrich stops for NSR lookup: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun enrichStopPointsViaNsr(
        stopPoints: Map<String, StopPoint>,
        stopAssignments: Map<String, String>
    ): Map<String, StopPoint> {
        val enriched = stopPoints.toMutableMap()

        for ((stopPointRef, quayRef) in stopAssignments) {
            if (!enriched.containsKey(stopPointRef)) {
                val coordinate = nsrService.getQuayCoordinates(quayRef)
                if (coordinate != null) {
                    enriched[stopPointRef] = StopPoint(
                        id = stopPointRef,
                        name = null,
                        coordinate = coordinate,
                        transportMode = null,
                        version = null
                    )
                    logger.debug("Enriched stop {} with coordinates from NSR quay {}", stopPointRef, quayRef)
                } else {
                    logger.warn("Could not resolve coordinates for stop {} via NSR quay {}", stopPointRef, quayRef)
                }
            }
        }

        return enriched
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

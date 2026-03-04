package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
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

    suspend fun processZipDirectly(zipData: ByteArray, modesFilter: Set<String>? = null, limit: Int? = null): Pair<ByteArray, Int> = processZipArchive(zipData, modesFilter, limit)

    private suspend fun processZipArchive(zipData: ByteArray, modesFilter: Set<String>? = null, limit: Int? = null): Pair<ByteArray, Int> {
        // PASS 1: Collect stops, assignments, stopDeclarationFile, existing service links,
        //         and raw JP pair refs (stop IDs only — coordinates not needed yet).
        val allStopPoints = mutableMapOf<String, StopPoint>()
        val allStopAssignments = mutableMapOf<String, String>()
        val stopDeclarationFile = mutableMapOf<String, String>()  // stopId → filename
        val stopVersions = mutableMapOf<String, String>()  // stopId → version from declaration
        val existingServiceLinksGlobal = mutableMapOf<String, String>()
        val rawPairRefs = mutableMapOf<String, Triple<String, String, TransportMode?>>()
        var firstXmlFile: String? = null

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                    if (firstXmlFile == null) firstXmlFile = entry.name
                    try {
                        val entryBytes = zis.readBytes()
                        val document = netexProcessor.parseXmlDocument(ByteArrayInputStream(entryBytes))
                        val stops = netexProcessor.extractStopPoints(document).associateBy { it.id }
                        val assignments = netexProcessor.extractStopAssignments(document)
                        allStopPoints.putAll(stops)
                        allStopAssignments.putAll(assignments)
                        // Record ALL declared ScheduledStopPoint IDs and versions (not just those
                        // with inline coordinates) so ServiceLinks are routed to the correct file
                        // and references use the correct version.
                        val declaredIdsAndVersions = netexProcessor.extractScheduledStopPointIdsAndVersions(document)
                        logger.debug("Pass 1 {}: {} ScheduledStopPoint IDs declared, {} with inline coords",
                            entry.name, declaredIdsAndVersions.size, stops.size)
                        declaredIdsAndVersions.forEach { (id, version) ->
                            stopDeclarationFile[id] = entry.name
                            stopVersions[id] = version
                        }
                        existingServiceLinksGlobal.putAll(netexProcessor.extractExistingServiceLinks(document))
                        netexProcessor.collectJpPairRefs(document).forEach { (k, v) -> rawPairRefs.putIfAbsent(k, v) }
                    } catch (e: Exception) {
                        logger.warn("Pass 1 failed for ${entry.name}: ${e.message}")
                    }
                }
                entry = zis.nextEntry
            }
        }

        // Enrich: resolve coordinates via NSR for stops with an assignment but no inline coordinates
        val enrichedStopPoints = enrichStopPointsViaNsr(allStopPoints, allStopAssignments, stopVersions)

        logger.info("ZIP pass 1: ${allStopPoints.size} stops, " +
                "${enrichedStopPoints.size - allStopPoints.size} enriched via NSR, " +
                "${allStopAssignments.size} assignments, ${rawPairRefs.size} raw JP pairs")

        // Resolve raw pair refs to StationPairs using enriched stop coordinates, skipping existing links
        val allNeededPairs = rawPairRefs
            .filterKeys { it !in existingServiceLinksGlobal }
            .mapNotNull { (pairKey, triple) ->
                val (fromRef, toRef, mode) = triple
                val from = enrichedStopPoints[fromRef]
                val to = enrichedStopPoints[toRef]
                if (from != null && to != null) pairKey to StationPair(from, to, mode) else null
            }.toMap()

        logger.info("ZIP planning: ${allNeededPairs.size} pairs need new service links")

        // ROUTE all pairs via OSRM globally
        val allServiceLinks = existingServiceLinksGlobal.toMutableMap()
        val routedPairs = linkedMapOf<String, Pair<StationPair, OSRMRoute?>>()
        var remaining = limit

        for ((pairKey, pair) in allNeededPairs) {
            if (remaining != null && remaining <= 0) break
            if (modesFilter != null && !modesFilter.contains(pair.transportMode?.name)) {
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: mode ${pair.transportMode} not in filter $modesFilter")
                continue
            }
            val route = try {
                osrmService.calculateRoute(pair.from.coordinate, pair.to.coordinate, pair.transportMode)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                logger.warn("OSRM route failed for ${pair.from.id} to ${pair.to.id}")
                null
            }
            if (route != null && route.distance > NetexProcessor.MAX_ROUTE_DISTANCE_METERS) {
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: distance ${route.distance}m exceeds max")
                continue
            }
            val serviceLinkId = NetexProcessor.computeServiceLinkId(pair)
            allServiceLinks[pairKey] = serviceLinkId
            routedPairs[pairKey] = pair to route
            if (remaining != null) remaining = (remaining - 1).coerceAtLeast(0)
        }

        logger.info("ZIP routing: ${routedPairs.size} new service links routed")

        // Assign each ServiceLink to the file that declares its from-stop's ScheduledStopPoint.
        // This ensures ServiceLink FromPointRef/ToPointRef identity constraints are satisfied per file.
        val serviceLinksByFile = mutableMapOf<String, MutableList<Pair<String, Pair<StationPair, OSRMRoute?>>>>()
        for ((pairKey, pairAndRoute) in routedPairs) {
            val (pair, _) = pairAndRoute
            val targetFile = stopDeclarationFile[pair.from.id]
                ?: stopDeclarationFile[pair.to.id]
                ?: firstXmlFile
                ?: continue
            serviceLinksByFile.getOrPut(targetFile) { mutableListOf() }.add(pairKey to pairAndRoute)
        }

        // WRITING PASS: inject ServiceLinks into target files, linksInSequence into JP files
        val outputBuffer = ByteArrayOutputStream()
        var totalAdded = 0

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            ZipOutputStream(outputBuffer).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryData = zis.readBytes()
                    if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                        logger.info("Processing XML entry: ${entry.name}")
                        val serviceLinksForFile = serviceLinksByFile[entry.name] ?: emptyList()
                        val (processedXml, addedCount) = netexProcessor.injectLinks(
                            entryData, serviceLinksForFile, allServiceLinks
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

    private suspend fun processSingleXml(xmlData: ByteArray, modesFilter: Set<String>? = null, limit: Int? = null): Pair<ByteArray, Int> {
        val enrichedStops = extractAndEnrichStops(xmlData)
        return netexProcessor.processNetexFile(ByteArrayInputStream(xmlData), enrichedStops, modesFilter, limit)
    }

    suspend fun processXmlContentDirectly(xmlContent: String, filename: String, modesFilter: Set<String>? = null, limit: Int? = null): DirectProcessingResult {
        return try {
            logger.info("Starting direct ServiceLink production for XML content: $filename")
            val xmlData = xmlContent.toByteArray(Charsets.UTF_8)
            val enrichedStops = extractAndEnrichStops(xmlData)
            val (processedXml, addedCount) = netexProcessor.processNetexFile(
                ByteArrayInputStream(xmlData),
                enrichedStops,
                modesFilter,
                limit
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
        stopAssignments: Map<String, String>,
        stopVersions: Map<String, String> = emptyMap()
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
                        version = stopVersions[stopPointRef]
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

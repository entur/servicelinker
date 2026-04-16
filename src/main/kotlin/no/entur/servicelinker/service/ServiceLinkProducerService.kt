package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.DirectProcessingResult
import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.ProcessedDataset
import no.entur.servicelinker.model.RoutedPair
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.plugins.MetadataExtractor
import no.entur.servicelinker.plugins.RawPairRef
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
    private val metadataExtractor: MetadataExtractor,
    @Value("\${gcs.bucket.output}") private val outputBucket: String,
) {
    private val logger = LoggerFactory.getLogger(ServiceLinkProducerService::class.java)

    suspend fun processDataset(
        inputBucket: String,
        objectName: String,
        modesFilter: Set<String>? = null,
    ): Int {
        MDC.put("stage", "processing/download")
        logger.info("Processing dataset: gs://{}/{}, modesFilter={}", inputBucket, objectName, modesFilter)

        val data = gcsService.downloadFile(inputBucket, objectName)

        val isZip =
            data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() &&
                data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

        if (modesFilter != null && modesFilter.isEmpty()) {
            logger.info("ServiceLinkModes is empty — skipping service link generation for {}", objectName)
            MDC.put("stage", "processing/upload")
            gcsService.uploadFile(data, outputBucket, objectName, if (isZip) "application/zip" else "application/xml")
            return 0
        }

        MDC.put("stage", "processing/parse")
        val processed =
            if (isZip || objectName.endsWith(".zip", ignoreCase = true)) {
                processZipArchive(data, modesFilter)
            } else {
                processSingleXml(data, modesFilter)
            }

        MDC.put("stage", "processing/upload")
        gcsService.uploadFile(
            processed.outputBytes,
            outputBucket,
            objectName,
            if (isZip) "application/zip" else "application/xml",
        )

        logger.info(
            "Dataset processing complete. Added {} ServiceLinks total. Output: gs://{}/{}",
            processed.addedCount,
            outputBucket,
            objectName,
        )
        return processed.addedCount
    }

    suspend fun processZipDirectly(
        zipData: ByteArray,
        modesFilter: Set<String>? = null,
        limit: Int? = null,
    ): ProcessedDataset = processZipArchive(zipData, modesFilter, limit)

    private suspend fun processZipArchive(
        zipData: ByteArray,
        modesFilter: Set<String>? = null,
        limit: Int? = null,
    ): ProcessedDataset {
        // Metadata extraction: stream each XML entry through the extractor individually. Per-file
        // error isolation (one malformed entry should not abort the archive) + bounded memory
        // (one entry's parse state at a time) + preserved ZIP iteration order for firstXmlFile.
        MDC.put("stage", "processing/parse")
        val allStopPoints = mutableMapOf<String, StopPoint>()
        val allStopAssignments = mutableMapOf<String, String>()
        val stopDeclarationFile = mutableMapOf<String, String>() // stopId → filename
        val stopVersions = mutableMapOf<String, String>() // stopId → version from declaration
        val existingServiceLinksGlobal = mutableMapOf<String, String>()
        val rawPairRefs = linkedMapOf<String, RawPairRef>()
        var firstXmlFile: String? = null

        val jpStopSequencesByFile = mutableMapOf<String, Map<String, List<String>>>()
        val existingSlijpByFile = mutableMapOf<String, Map<String, Map<String, ExistingSlijpInfo>>>()
        val declaredStopIdsByFile = mutableMapOf<String, Set<String>>()

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                    if (firstXmlFile == null) firstXmlFile = entry.name
                    val entryBytes = zis.readBytes()
                    try {
                        val doc = metadataExtractor.extract(entry.name, entryBytes)
                        allStopPoints.putAll(doc.stopPoints.associateBy { it.id })
                        allStopAssignments.putAll(doc.assignments)
                        logger.debug(
                            "Metadata extraction {}: {} ScheduledStopPoint IDs declared, {} with inline coords",
                            entry.name,
                            doc.stopIdsAndVersions.size,
                            doc.stopPoints.size,
                        )
                        doc.stopIdsAndVersions.forEach { (id, version) ->
                            stopDeclarationFile[id] = entry.name
                            stopVersions[id] = version
                        }
                        existingServiceLinksGlobal.putAll(doc.existingServiceLinks)
                        doc.rawPairRefs().forEach { (k, v) -> rawPairRefs.putIfAbsent(k, v) }

                        jpStopSequencesByFile[entry.name] = doc.stopSequences
                        existingSlijpByFile[entry.name] = doc.existingSlijp
                        declaredStopIdsByFile[entry.name] = doc.stopIdsAndVersions.keys
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Metadata extraction failed for ${entry.name}: ${e.message}")
                    }
                }
                entry = zis.nextEntry
            }
        }

        // Enrich: resolve coordinates via NSR for stops with an assignment but no inline coordinates
        MDC.put("stage", "processing/enrich")
        val enrichedStopPoints = enrichStopPointsViaNsr(allStopPoints, allStopAssignments, stopVersions)

        logger.info(
            "ZIP pass 1: ${allStopPoints.size} stops, " +
                "${enrichedStopPoints.size - allStopPoints.size} enriched via NSR, " +
                "${allStopAssignments.size} assignments, ${rawPairRefs.size} raw JP pairs",
        )

        // Resolve raw pair refs to StationPairs using enriched stop coordinates, skipping existing links
        val allNeededPairs =
            rawPairRefs
                .filterKeys { it !in existingServiceLinksGlobal }
                .mapNotNull { (pairKey, ref) ->
                    val from = enrichedStopPoints[ref.fromRef]
                    val to = enrichedStopPoints[ref.toRef]
                    if (from != null && to != null) pairKey to StationPair(from, to, ref.mode) else null
                }.toMap()

        logger.info("ZIP planning: ${allNeededPairs.size} pairs need new service links")

        // ROUTE all pairs via OSRM globally
        MDC.put("stage", "processing/route")
        val allServiceLinks = existingServiceLinksGlobal.toMutableMap()
        val routedPairs = netexProcessor.routePairs(allNeededPairs, modesFilter, limit)
        for ((pairKey, routed) in routedPairs) {
            allServiceLinks[pairKey] = NetexProcessor.computeServiceLinkId(routed.stationPair)
        }

        logger.info("ZIP routing: ${routedPairs.size} new service links routed")

        // Assign each ServiceLink to the file that declares its from-stop's ScheduledStopPoint.
        val serviceLinksByFile = mutableMapOf<String, MutableList<Pair<String, RoutedPair>>>()
        for ((pairKey, routed) in routedPairs) {
            val pair = routed.stationPair
            val targetFile =
                stopDeclarationFile[pair.from.id]
                    ?: stopDeclarationFile[pair.to.id]
                    ?: firstXmlFile
                    ?: continue
            serviceLinksByFile.getOrPut(targetFile) { mutableListOf() }.add(pairKey to routed)
        }

        // WRITING PASS: stream through ZIP, applying StAX transformation per XML file
        MDC.put("stage", "processing/inject")
        val outputBuffer = ByteArrayOutputStream()
        var totalAdded = 0

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            ZipOutputStream(outputBuffer).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (Thread.interrupted()) throw InterruptedException("Processing cancelled")
                    val entryData = zis.readBytes()
                    if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                        val serviceLinksForFile = serviceLinksByFile[entry.name] ?: emptyList()
                        logger.info(
                            "Processing XML entry: {} ({} bytes, {} links to inject)",
                            entry.name,
                            entryData.size,
                            serviceLinksForFile.size,
                        )
                        val entryStart = System.currentTimeMillis()

                        val plan =
                            netexProcessor.buildZipInjectionPlan(
                                serviceLinksForFile,
                                allServiceLinks,
                                jpStopSequencesByFile[entry.name] ?: emptyMap(),
                                existingSlijpByFile[entry.name] ?: emptyMap(),
                                declaredStopIdsByFile[entry.name] ?: emptySet(),
                            )
                        val processedXml = netexProcessor.applyPlan(entryData, plan)
                        val addedCount = serviceLinksForFile.size

                        val entryMs = System.currentTimeMillis() - entryStart
                        logger.info(
                            "Completed XML entry: {} in {}ms ({} bytes out, {} links added)",
                            entry.name,
                            entryMs,
                            processedXml.size,
                            addedCount,
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

        return ProcessedDataset(outputBuffer.toByteArray(), totalAdded)
    }

    private suspend fun processSingleXml(
        xmlData: ByteArray,
        modesFilter: Set<String>? = null,
        limit: Int? = null,
    ): ProcessedDataset {
        val enrichedStops = extractAndEnrichStops(xmlData)
        return netexProcessor.processNetexFile(xmlData, enrichedStops, modesFilter, limit)
    }

    suspend fun processXmlContentDirectly(
        xmlContent: String,
        filename: String,
        modesFilter: Set<String>? = null,
        limit: Int? = null,
    ): DirectProcessingResult =
        try {
            logger.info("Starting direct ServiceLink production for XML content: $filename")
            val xmlData = xmlContent.toByteArray(Charsets.UTF_8)
            val enrichedStops = extractAndEnrichStops(xmlData)
            val (processedXml, addedCount) =
                netexProcessor.processNetexFile(
                    xmlData,
                    enrichedStops,
                    modesFilter,
                    limit,
                )
            logger.info("Successfully processed XML content. Added $addedCount ServiceLinks for file: $filename")
            DirectProcessingResult(
                success = true,
                processedXml = String(processedXml, Charsets.UTF_8),
                addedServiceLinksCount = addedCount,
            )
        } catch (e: Exception) {
            logger.error("Error processing XML content directly: $filename", e)
            DirectProcessingResult(
                success = false,
                processedXml = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}",
            )
        }

    private suspend fun extractAndEnrichStops(xmlData: ByteArray): Map<String, StopPoint> =
        try {
            val doc = metadataExtractor.extract(NetexProcessor.SINGLE_DOC_NAME, xmlData)
            val stops = doc.stopPoints.associateBy { it.id }
            enrichStopPointsViaNsr(stops, doc.assignments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to extract/enrich stops for NSR lookup: ${e.message}")
            emptyMap()
        }

    private suspend fun enrichStopPointsViaNsr(
        stopPoints: Map<String, StopPoint>,
        stopAssignments: Map<String, String>,
        stopVersions: Map<String, String> = emptyMap(),
    ): Map<String, StopPoint> {
        val enriched = stopPoints.toMutableMap()

        for ((stopPointRef, quayRef) in stopAssignments) {
            if (!enriched.containsKey(stopPointRef)) {
                val coordinate = nsrService.getQuayCoordinates(quayRef)
                if (coordinate != null) {
                    enriched[stopPointRef] =
                        StopPoint(
                            id = stopPointRef,
                            name = null,
                            coordinate = coordinate,
                            transportMode = null,
                            version = stopVersions[stopPointRef],
                        )
                    logger.debug("Enriched stop {} with coordinates from NSR quay {}", stopPointRef, quayRef)
                } else {
                    logger.warn("Could not resolve coordinates for stop {} via NSR quay {}", stopPointRef, quayRef)
                }
            }
        }

        return enriched
    }

    fun getRoutingCacheStatistics(): Map<String, Any> =
        mapOf(
            "cacheSize" to osrmService.getCacheSize(),
            "timestamp" to System.currentTimeMillis(),
        )

    fun clearRoutingCache() {
        osrmService.clearCache()
        logger.info("Routing cache cleared")
    }
}

package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.handlers.InjectionHandlerFactory
import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.ProcessedDataset
import no.entur.servicelinker.model.RoutedPair
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.plugins.Pass1PluginExtractor
import org.entur.netex.tools.lib.config.FilterConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import org.entur.netex.tools.lib.NetexProcessor as NetexToolsProcessor

@Service
class NetexProcessor(
    private val osrmService: OSRMService,
    private val pass1Extractor: Pass1PluginExtractor,
) {
    private val logger = LoggerFactory.getLogger(NetexProcessor::class.java)

    suspend fun processNetexFile(
        inputStream: InputStream,
        externalStopPoints: Map<String, StopPoint> = emptyMap(),
        modesFilter: Set<String>? = null,
        limit: Int? = null,
    ): ProcessedDataset {
        logger.info("Starting NeTEx file processing")
        @Suppress("BlockingMethodInNonBlockingContext") // Always a ByteArrayInputStream in practice
        val inputBytes = inputStream.readAllBytes()
        val pass1 = pass1Extractor.extract(SINGLE_DOC_NAME, inputBytes)

        // Merge: local takes priority over external (which may include NSR-enriched stops)
        val stopPointsMap = externalStopPoints + pass1.stopPoints.associateBy { it.id }

        // Collect unique consecutive pairs from all JourneyPatterns
        val neededPairs = mutableMapOf<String, StationPair>()
        for ((jpId, stopRefIds) in pass1.stopSequences) {
            val jpMode = pass1.jpModeMap[jpId]
            for (i in 0 until stopRefIds.size - 1) {
                val fromRef = stopRefIds[i]
                val toRef = stopRefIds[i + 1]
                val pairKey = "${fromRef}_$toRef"
                if (!pass1.existingServiceLinks.containsKey(pairKey) && !neededPairs.containsKey(pairKey)) {
                    val from = stopPointsMap[fromRef]
                    val to = stopPointsMap[toRef]
                    if (from != null && to != null) {
                        neededPairs[pairKey] = StationPair(from, to, jpMode)
                    }
                }
            }
        }
        logger.info("Found ${neededPairs.size} consecutive pairs that need new service links")

        // allServiceLinks: pairKey → serviceLinkId (existing + newly created)
        val allServiceLinks = pass1.existingServiceLinks.toMutableMap()

        val routedPairs = routePairs(neededPairs, modesFilter, limit)
        val newServiceLinkData = LinkedHashMap<String, ServiceLinkData>()
        for ((pairKey, routed) in routedPairs) {
            val data = createServiceLinkData(routed.stationPair, routed.route)
            allServiceLinks[pairKey] = data.id
            newServiceLinkData[pairKey] = data
        }
        val addedCount = newServiceLinkData.size

        if (addedCount == 0) {
            logger.info("NeTEx processing completed. No service links to add")
            return ProcessedDataset(inputBytes, 0)
        }

        val plan =
            buildInjectionPlan(
                newServiceLinkData = newServiceLinkData,
                allServiceLinks = allServiceLinks,
                jpStopSequences = pass1.stopSequences,
                existingSlijpEntries = pass1.existingSlijp,
                declaredStopIds = pass1.stopIdsAndVersions.keys,
            )

        val modifiedXml = applyPlan(inputBytes, plan)
        logger.info("NeTEx processing completed. Added $addedCount service links")
        return ProcessedDataset(modifiedXml, addedCount)
    }

    /** Route stop pairs via OSRM, applying mode filter and distance/limit constraints. */
    suspend fun routePairs(
        pairs: Map<String, StationPair>,
        modesFilter: Set<String>?,
        limit: Int?,
    ): LinkedHashMap<String, RoutedPair> {
        val result = linkedMapOf<String, RoutedPair>()
        var remaining = limit
        for ((pairKey, pair) in pairs) {
            if (remaining != null && remaining <= 0) break
            if (modesFilter != null && !modesFilter.contains(pair.transportMode?.name)) {
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: mode ${pair.transportMode} not in filter $modesFilter")
                continue
            }
            val route =
                try {
                    osrmService.calculateRoute(pair.from.coordinate, pair.to.coordinate, pair.transportMode)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    logger.warn("OSRM route failed for ${pair.from.id} to ${pair.to.id}")
                    null
                }
            if (route != null && route.distance > MAX_ROUTE_DISTANCE_METERS) {
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: distance ${route.distance}m exceeds max")
                continue
            }
            result[pairKey] = RoutedPair(pair, route)
            if (remaining != null) remaining = (remaining - 1).coerceAtLeast(0)
        }
        return result
    }

    /** Apply a pre-built InjectionPlan to raw XML bytes via netex-tools handlers. */
    fun applyPlan(
        input: ByteArray,
        plan: InjectionPlan,
    ): ByteArray {
        if (plan.isEmpty) return input

        val handlers = InjectionHandlerFactory.buildHandlerMap(plan)
        val filterConfig =
            FilterConfig(
                customElementHandlers = handlers,
                // linksInSequence elements whose SLIJP children are excluded from the
                // entity selection become childless; this rule drops the empty wrapper.
                // Note: matches by element name, so linksInSequence inside Route (which
                // uses RouteLinkInSequence, not ServiceLinkInJourneyPattern) would also
                // be affected — acceptable since Norwegian NeTEx data does not use that.
                elementsRequiredChildren =
                    if (plan.journeyPatternLinks.isNotEmpty()) {
                        mapOf("linksInSequence" to listOf("ServiceLinkInJourneyPattern"))
                    } else {
                        emptyMap()
                    },
            )
        val processor = NetexToolsProcessor(filterConfig = filterConfig)
        val docName = "doc.xml"
        val docs = mapOf(docName to input)
        processor.buildEntityModel(docs)

        // Exclude existing SLIJP entities whose parent JP has planned replacements.
        // This drops the existing linksInSequence subtree so the JourneyPatternHandler
        // can emit the merged replacement.
        val slijpIdsToExclude =
            processor.model
                .getEntitiesOfType("ServiceLinkInJourneyPattern")
                .filter { plan.journeyPatternLinks.containsKey(it.parent?.id) }
                .map { it.id }
                .toSet()

        val (entitySelection, refSelection) = processor.selectEntities()

        val modifiedSelection =
            if (slijpIdsToExclude.isNotEmpty()) {
                val slijpMap = entitySelection.selection["ServiceLinkInJourneyPattern"]
                if (slijpMap != null) {
                    entitySelection.withReplaced(
                        "ServiceLinkInJourneyPattern",
                        slijpMap.filterKeys { it !in slijpIdsToExclude },
                    )
                } else {
                    entitySelection
                }
            } else {
                entitySelection
            }

        val result = processor.exportToByteArrays(docs, modifiedSelection, refSelection)
        val output = requireNotNull(result.documents[docName])
        return rewriteGmlPrefix(output)
    }

    /**
     * Rewrite any `gml:` GML namespace prefix to the Entur convention `gis:`.
     * The SAX pipeline passes through whatever prefix the input uses; this
     * normalizes the output to always use `gis:` in a single pass.
     */
    private fun rewriteGmlPrefix(xml: ByteArray): ByteArray {
        val text = String(xml, Charsets.UTF_8)
        // Coarse guard: any gml: start tag implies gml prefix usage in elements
        // and attributes. Matching in CDATA/comments is harmless — the regex
        // only rewrites actual prefix contexts.
        if ("<gml:" !in text) return xml
        return GML_PREFIX_PATTERN.replace(text, "gis").toByteArray(Charsets.UTF_8)
    }

    /** Create a ServiceLinkData object from a routed stop pair. Pure data, no DOM. */
    fun createServiceLinkData(
        pair: StationPair,
        route: OSRMRoute?,
    ): ServiceLinkData {
        val (codespace, fromId) = splitNetexId(pair.from.id)
        val (_, toId) = splitNetexId(pair.to.id)
        val timestamp = TIMESTAMP_FORMAT.format(java.time.LocalDateTime.now())
        val serviceLinkId = computeServiceLinkId(pair)

        val fromCoord = listOf(pair.from.coordinate.longitude, pair.from.coordinate.latitude)
        val toCoord = listOf(pair.to.coordinate.longitude, pair.to.coordinate.latitude)
        val coordinates =
            if (route != null) {
                val osrmCoords = route.coordinates.toMutableList()
                osrmCoords[0] = fromCoord
                osrmCoords[osrmCoords.lastIndex] = toCoord
                osrmCoords
            } else {
                listOf(fromCoord, toCoord)
            }
        val posListString = coordinates.joinToString(" ") { coord -> "${coord[1]} ${coord[0]}" }

        return ServiceLinkData(
            id = serviceLinkId,
            version = "1",
            distance = route?.distance?.toInt(),
            projectionId = "$codespace:LinkSequenceProjection:${timestamp}_${fromId}_$toId",
            gmlLineStringId = "lsp_${fromId}_$toId",
            posList = posListString,
            fromPointRef = pair.from.id,
            fromPointVersion = pair.from.version ?: "1",
            toPointRef = pair.to.id,
            toPointVersion = pair.to.version ?: "1",
        )
    }

    /** Build an InjectionPlan that captures all modifications for a single file. */
    fun buildInjectionPlan(
        newServiceLinkData: Map<String, ServiceLinkData>,
        allServiceLinks: Map<String, String>,
        jpStopSequences: Map<String, List<String>>,
        existingSlijpEntries: Map<String, Map<String, ExistingSlijpInfo>>,
        declaredStopIds: Set<String>,
    ): InjectionPlan {
        // Stub stops: referenced by new ServiceLinks but not declared in this file
        val referencedStops = mutableMapOf<String, String>()
        for (data in newServiceLinkData.values) {
            referencedStops[data.fromPointRef] = data.fromPointVersion
            referencedStops[data.toPointRef] = data.toPointVersion
        }
        val stubStops = referencedStops.filterKeys { it !in declaredStopIds }

        // Per-JP merged linksInSequence
        val jpLinks = mutableMapOf<String, List<SlijpEntry>>()
        for ((jpId, stopRefIds) in jpStopSequences) {
            val existing = existingSlijpEntries[jpId] ?: emptyMap()
            val entries = mutableListOf<SlijpEntry>()
            var order = 1
            val codespace = jpId.split(":").firstOrNull() ?: continue
            val jpLocalId = jpId.split(":").lastOrNull() ?: ""

            for (i in 0 until stopRefIds.size - 1) {
                val fromRef = stopRefIds[i]
                val toRef = stopRefIds[i + 1]
                val pairKey = "${fromRef}_$toRef"
                val serviceLinkId = allServiceLinks[pairKey] ?: continue

                val existingEntry = existing[serviceLinkId]
                if (existingEntry != null) {
                    entries.add(
                        SlijpEntry(
                            id = existingEntry.id,
                            version = existingEntry.version,
                            order = order,
                            serviceLinkRef = serviceLinkId,
                            serviceLinkRefVersion = existingEntry.serviceLinkRefVersion,
                        ),
                    )
                } else {
                    val fromLocalId = fromRef.split(":").lastOrNull() ?: fromRef
                    val toLocalId = toRef.split(":").lastOrNull() ?: toRef
                    val slijpId = "$codespace:ServiceLinkInJourneyPattern:${jpLocalId}_${fromLocalId}_to_$toLocalId"
                    entries.add(
                        SlijpEntry(
                            id = slijpId,
                            version = "1",
                            order = order,
                            serviceLinkRef = serviceLinkId,
                            serviceLinkRefVersion = null,
                        ),
                    )
                }
                order++
            }
            if (entries.isNotEmpty()) {
                jpLinks[jpId] = entries
            }
        }

        return InjectionPlan(
            newServiceLinks = newServiceLinkData.values.toList(),
            stubScheduledStopPoints = stubStops,
            journeyPatternLinks = jpLinks,
        )
    }

    /** Build an InjectionPlan for a ZIP file entry using pre-routed data. */
    fun buildZipInjectionPlan(
        newServiceLinksForThisDoc: List<Pair<String, RoutedPair>>,
        allServiceLinks: Map<String, String>,
        jpStopSequences: Map<String, List<String>>,
        existingSlijpEntries: Map<String, Map<String, ExistingSlijpInfo>>,
        declaredStopIds: Set<String>,
    ): InjectionPlan {
        val newData = LinkedHashMap<String, ServiceLinkData>()
        for ((pairKey, routed) in newServiceLinksForThisDoc) {
            newData[pairKey] = createServiceLinkData(routed.stationPair, routed.route)
        }
        return buildInjectionPlan(
            newData,
            allServiceLinks,
            jpStopSequences,
            existingSlijpEntries,
            declaredStopIds,
        )
    }

    companion object {
        /** Synthetic document name used when processing a single XML stream via the plugin extractor. */
        const val SINGLE_DOC_NAME = "input.xml"

        private val TIMESTAMP_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMddHHmmss")

        fun computeServiceLinkId(pair: StationPair): String {
            val (codespace, fromId) = splitNetexId(pair.from.id)
            val (_, toId) = splitNetexId(pair.to.id)
            return "$codespace:ServiceLink:$fromId-$toId"
        }

        /** Split a NeTEx ID like "FIN:ServiceLink:42" into (codespace, localId). */
        fun splitNetexId(id: String): Pair<String, String> {
            val parts = id.split(":")
            return parts.first() to (parts.lastOrNull() ?: id)
        }

        const val MAX_ROUTE_DISTANCE_METERS = 5_000_000 // 5000 km — sanity limit

        /** Matches `gml` when used as an XML namespace prefix — in declarations,
         *  element tags, or namespace-qualified attributes. Single-pass replacement. */
        private val GML_PREFIX_PATTERN = Regex("""(?<=xmlns:|</?| )gml(?=[:=])""")
    }
}

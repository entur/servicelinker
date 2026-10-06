package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.handlers.InjectionHandlerFactory
import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.IncompleteJourneyPatternException
import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.MissingPair
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.ProcessedDataset
import no.entur.servicelinker.model.RoutedPair
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import no.entur.servicelinker.plugins.MetadataExtractor
import no.entur.servicelinker.util.DouglasPeucker
import org.entur.netex.tools.lib.config.FilterConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.entur.netex.tools.lib.NetexProcessor as NetexToolsProcessor

@Service
class NetexProcessor(
    private val osrmService: OSRMService,
    private val metadataExtractor: MetadataExtractor,
    private val meters: ServicelinkerMetrics,
    @Value("\${servicelinker.max-route-distance-meters:5000000}")
    private val maxRouteDistanceMeters: Long = MAX_ROUTE_DISTANCE_METERS_DEFAULT,
) {
    private val logger = LoggerFactory.getLogger(NetexProcessor::class.java)

    suspend fun processNetexFile(
        inputBytes: ByteArray,
        externalStopPoints: Map<String, StopPoint> = emptyMap(),
        modesFilter: Set<String>? = null,
        limit: Int? = null,
        transientlyUnresolvedQuays: Set<String> = emptySet(),
    ): ProcessedDataset {
        logger.info("Starting NeTEx file processing")
        val metadata = metadataExtractor.extract(SINGLE_DOC_NAME, inputBytes)

        // Merge: local takes priority over external (which may include NSR-enriched stops)
        val stopPointsMap = externalStopPoints + metadata.stopPoints.associateBy { it.id }

        // Collect unique consecutive pairs from all JourneyPatterns
        val neededPairs = mutableMapOf<String, StationPair>()
        for ((jpId, stopRefIds) in metadata.stopSequences) {
            val jpMode = metadata.jpModeMap[jpId]
            for (i in 0 until stopRefIds.size - 1) {
                val fromRef = stopRefIds[i]
                val toRef = stopRefIds[i + 1]
                val pairKey = "${fromRef}_$toRef"
                if (!metadata.existingServiceLinks.containsKey(pairKey) && !neededPairs.containsKey(pairKey)) {
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
        val allServiceLinks = metadata.existingServiceLinks.toMutableMap()

        val routedPairs = routePairs(neededPairs, modesFilter, limit)
        val newServiceLinkData = LinkedHashMap<String, ServiceLinkData>()
        for ((pairKey, routed) in routedPairs) {
            val data = createServiceLinkData(routed.stationPair, routed.route)
            allServiceLinks[pairKey] = data.id
            newServiceLinkData[pairKey] = data
        }
        val addedCount = newServiceLinkData.size

        warnOnPairModeConflicts(logger, detectPairModeConflicts(metadata.stopSequences, metadata.jpModeMap))

        checkJourneyPatternIntegrity(
            metadata.stopSequences,
            allServiceLinks,
            externalStopPoints + stopPointsMap,
            metadata.assignments,
            transientlyUnresolvedQuays,
        )

        if (addedCount == 0) {
            logger.info("NeTEx processing completed. No service links to add")
            return ProcessedDataset(inputBytes, 0)
        }

        val plan =
            buildInjectionPlan(
                newServiceLinkData = newServiceLinkData,
                allServiceLinks = allServiceLinks,
                jpStopSequences = metadata.stopSequences,
                existingSlijpEntries = metadata.existingSlijp,
                declaredStopIds = metadata.stopIdsAndVersions.keys,
            )

        val modifiedXml = applyPlan(inputBytes, plan)
        logger.info("NeTEx processing completed. Added $addedCount service links")
        return ProcessedDataset(modifiedXml, addedCount)
    }

    /**
     * Route stop pairs via OSRM. Every input pair appears in the result — pairs that can't be
     * routed (mode not in [modesFilter], OSRM error, or absurd distance) get a null route so
     * [createServiceLinkData] emits a straight-line ServiceLink. This keeps every JP's
     * linksInSequence count consistent with its pointsInSequence, regardless of routing outcome.
     */
    suspend fun routePairs(
        pairs: Map<String, StationPair>,
        modesFilter: Set<String>?,
        limit: Int?,
    ): LinkedHashMap<String, RoutedPair> {
        val result = linkedMapOf<String, RoutedPair>()
        var remaining = limit
        for ((pairKey, pair) in pairs) {
            if (remaining != null && remaining <= 0) break
            result[pairKey] = RoutedPair(pair, computeRouteOrFallback(pair, modesFilter))
            if (remaining != null) remaining = (remaining - 1).coerceAtLeast(0)
        }
        return result
    }

    /** Returns a routed [OSRMRoute] or null for the straight-line fallback (with a reason logged). */
    private suspend fun computeRouteOrFallback(
        pair: StationPair,
        modesFilter: Set<String>?,
    ): OSRMRoute? {
        val mode = pair.transportMode?.name
        if (modesFilter != null && !modesFilter.contains(mode)) {
            logger.debug(
                "Pair {} → {}: mode {} not in {} — using straight line",
                pair.from.id,
                pair.to.id,
                pair.transportMode,
                modesFilter,
            )
            meters.straightLineFallback(mode, "mode-filtered")
            return null
        }
        val route =
            try {
                osrmService.calculateRoute(pair.from.coordinate, pair.to.coordinate, pair.transportMode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logger.warn("OSRM route failed for ${pair.from.id} to ${pair.to.id} — using straight line")
                meters.straightLineFallback(mode, "osrm-error")
                return null
            }
        // calculateRoute swallows its own HTTP failures and returns null, so this is the only place
        // a routing failure becomes visible. Warn: silently drawn straight lines are how the
        // September 2026 hur geometry reached NOR-WAY before it reached us.
        if (route == null) {
            logger.warn(
                "No OSRM route for {} → {} (mode {}) — using straight line",
                pair.from.id,
                pair.to.id,
                mode,
            )
            meters.straightLineFallback(mode, "no-route")
            return null
        }
        if (route.distance > maxRouteDistanceMeters) {
            logger.warn(
                "OSRM returned implausible {}m for {} → {} — using straight line",
                route.distance,
                pair.from.id,
                pair.to.id,
            )
            meters.straightLineFallback(mode, "implausible-distance")
            return null
        }
        // A route many times longer than the direct line usually means a stop snapped to the wrong
        // road, e.g. across a fjord. Short pairs are exempt: loops and one-way streets inflate the ratio.
        val direct = pair.from.coordinate.haversineDistanceTo(pair.to.coordinate)
        if (direct >= MIN_DETOUR_CHECK_DIRECT_METERS && route.distance > MAX_DETOUR_RATIO * direct) {
            logger.warn(
                "OSRM route {}m is more than {}x the direct {}m for {} -> {}, using straight line",
                route.distance,
                MAX_DETOUR_RATIO,
                direct.toInt(),
                pair.from.id,
                pair.to.id,
            )
            meters.straightLineFallback(mode, "implausible-detour")
            return null
        }
        return route
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

        val fromCoord = pair.from.coordinate
        val toCoord = pair.to.coordinate
        val osrmPath = route?.path.orEmpty()
        val coordinates =
            if (osrmPath.size >= 2) {
                val osrmCoords = osrmPath.toMutableList()
                osrmCoords[0] = fromCoord
                osrmCoords[osrmCoords.lastIndex] = toCoord
                // OSRM emits a point per road geometry node, and most of them are collinear with
                // their neighbours: they carry no shape information and only bloat the NeTEx output.
                // Simplify after snapping the endpoints, so the tolerance holds for what we emit.
                DouglasPeucker.simplify(osrmCoords, simplifyToleranceMeters(route?.distance))
            } else {
                listOf(fromCoord, toCoord)
            }
        val posListString = coordinates.joinToString(" ") { coord -> "${coord.latitude} ${coord.longitude}" }

        // Fall back to haversine when OSRM didn't provide a route — antu's geometry plausibility
        // checks expect Distance, and a NeTEx ServiceLink without it is structurally complete but
        // less useful downstream.
        val distance =
            route?.distance?.toInt() ?: pair.from.coordinate
                .haversineDistanceTo(pair.to.coordinate)
                .toInt()

        return ServiceLinkData(
            id = serviceLinkId,
            version = "1",
            distance = distance,
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
                val serviceLinkId = allServiceLinks[pairKey] ?: error("Missing ServiceLink for pair $pairKey in JP $jpId")

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

        /** Floor for the ServiceLink geometry tolerance, at or below the precision OSRM reports. */
        private const val MIN_SIMPLIFY_TOLERANCE_METERS = 1.0

        /**
         * Geometry is simplified to 1 part in [SIMPLIFY_RATIO] of the route's own length, with a
         * [MIN_SIMPLIFY_TOLERANCE_METERS] floor. A fixed 1 m tolerance keeps a share of points that
         * grows with route length, so a 700 km coach leg would carry ~9000 of them; tying the
         * tolerance to the length instead holds every link to roughly a thousand points, and keeps
         * the error a constant fraction of what is drawn - well under a pixel at the zoom where the
         * whole link is on screen.
         */
        private const val SIMPLIFY_RATIO = 20_000.0

        private fun simplifyToleranceMeters(routeDistanceMeters: Double?): Double =
            maxOf(MIN_SIMPLIFY_TOLERANCE_METERS, (routeDistanceMeters ?: 0.0) / SIMPLIFY_RATIO)

        /**
         * Verify every consecutive stop pair in every JourneyPattern has a resolvable ServiceLink.
         * Throws [IncompleteJourneyPatternException] otherwise — preventing servicelinker from
         * shipping output that antu's stop-count vs. link-count check would reject.
         *
         * [stopPoints] and [stopAssignments] are passed in to enrich the error message: the user-
         * visible failure can then quote the NSR quay refs (so an operator can paste them into
         * the NSR admin UI) and aggregate by failure cause (missing PSA vs failed NSR lookup).
         */
        fun checkJourneyPatternIntegrity(
            jpStopSequences: Map<String, List<String>>,
            allServiceLinks: Map<String, String>,
            stopPoints: Map<String, StopPoint> = emptyMap(),
            stopAssignments: Map<String, String> = emptyMap(),
            transientlyUnresolvedQuays: Set<String> = emptySet(),
        ) {
            // Same predicate as `unresolvedQuays`, for one stop ref: PSA-backed, no coordinate,
            // and the quay is one NSR failed transiently on.
            val blamesThrottling = { stopRef: String ->
                stopRef !in stopPoints && stopAssignments[stopRef]?.let { it in transientlyUnresolvedQuays } == true
            }

            val missing = mutableListOf<MissingPair>()
            var totalMissing = 0
            // Evaluated over every missing pair, not over the capped sample: a dataset with more
            // than HARD_CAP_MISSING_PAIRS misses can have all its throttled pairs in the tail.
            var throttledPairFound = false
            for ((jpId, stopRefIds) in jpStopSequences) {
                for (i in 0 until stopRefIds.size - 1) {
                    val fromRef = stopRefIds[i]
                    val toRef = stopRefIds[i + 1]
                    if (!allServiceLinks.containsKey("${fromRef}_$toRef")) {
                        totalMissing++
                        if (!throttledPairFound) {
                            throttledPairFound = blamesThrottling(fromRef) || blamesThrottling(toRef)
                        }
                        // Cap collection at HARD_CAP — the message uses totalMissing for the count,
                        // so we lose only the tail of the sample list, not the headline numbers.
                        if (missing.size < HARD_CAP_MISSING_PAIRS) {
                            missing.add(MissingPair(jpId, fromRef, toRef))
                        }
                    }
                }
            }
            if (missing.isEmpty()) return

            val unresolvedQuays = unresolvedQuays(missing, stopPoints, stopAssignments)
            val throttled = unresolvedQuays.intersect(transientlyUnresolvedQuays)
            throw IncompleteJourneyPatternException(
                missing.take(MAX_REPORTED_MISSING_PAIRS),
                formatIntegrityError(totalMissing, missing, stopPoints, stopAssignments, unresolvedQuays, throttled),
                retryable = throttledPairFound,
            )
        }

        /**
         * Quays that have a PassengerStopAssignment but no coordinate in [stopPoints].
         *
         * Order matters: filter by SSP ref, THEN map to quay. [stopPoints] is keyed by SSP ref,
         * not quay ref, so mapping to quay first would make the filter a no-op that blames every
         * PSA-backed quay in the sample — even ones that resolved and are implicated only by
         * neighbouring an unresolved stop. "Unresolved" == absent from the enriched map.
         */
        private fun unresolvedQuays(
            sample: List<MissingPair>,
            stopPoints: Map<String, StopPoint>,
            stopAssignments: Map<String, String>,
        ): Set<String> =
            sample
                .flatMap { listOf(it.fromRef, it.toRef) }
                .filter { it in stopAssignments && it !in stopPoints }
                .mapNotNull { stopAssignments[it] }
                .toSet()

        private fun formatIntegrityError(
            totalMissing: Int,
            sample: List<MissingPair>,
            stopPoints: Map<String, StopPoint>,
            stopAssignments: Map<String, String>,
            unresolvedQuays: Set<String>,
            throttledQuays: Set<String>,
        ): String {
            // `sample` is capped at HARD_CAP_MISSING_PAIRS so we only see a representative subset
            // — counts and per-JP groupings are over what we sampled. `totalMissing` is the truth.
            val byJp = sample.groupBy { it.jpId }
            val distinctStops =
                buildSet {
                    for (p in sample) {
                        add(p.fromRef)
                        add(p.toRef)
                    }
                }
            val (withPsa, withoutPsa) = distinctStops.partition { it in stopAssignments }

            val sampleLines =
                byJp.entries.take(SAMPLE_JPS).joinToString("\n") { (jpId, pairs) ->
                    val examples =
                        pairs.take(SAMPLE_PAIRS).joinToString(", ") { p ->
                            val fromQ = stopAssignments[p.fromRef]?.let { " ($it)" } ?: ""
                            val toQ = stopAssignments[p.toRef]?.let { " ($it)" } ?: ""
                            "${p.fromRef}$fromQ → ${p.toRef}$toQ"
                        }
                    val extra = if (pairs.size > SAMPLE_PAIRS) " (+${pairs.size - SAMPLE_PAIRS} more)" else ""
                    "  JP $jpId — ${pairs.size} missing: $examples$extra"
                }
            val moreJps = if (byJp.size > SAMPLE_JPS) "\n  ... and ${byJp.size - SAMPLE_JPS} more JP(s)" else ""

            return buildString {
                append("Cannot produce valid JourneyPatterns: ")
                append("$totalMissing consecutive stop pair(s) across at least ${byJp.size} JP(s) ")
                append("have no ServiceLink.\n")
                if (totalMissing > sample.size) {
                    append("  (Sampled the first ${sample.size}; stats below are over the sample.)\n")
                }
                append("  Distinct stops affected: ${distinctStops.size}")
                append(" (${withoutPsa.size} without PassengerStopAssignment")
                append(", ${withPsa.size} with PassengerStopAssignment)\n")
                if (unresolvedQuays.isNotEmpty()) {
                    val quaySample = unresolvedQuays.take(SAMPLE_QUAYS).joinToString(", ")
                    val moreQuays = if (unresolvedQuays.size > SAMPLE_QUAYS) " (+${unresolvedQuays.size - SAMPLE_QUAYS} more)" else ""
                    append("  Quays with PSA but no resolved coordinate (missing from NSR or the lookup failed): $quaySample$moreQuays\n")
                }
                if (throttledQuays.isNotEmpty()) {
                    append("  ${throttledQuays.size} of those failed transiently (NSR rate-limit/timeout), not because they are absent")
                    append(" — this run is retryable.\n")
                }
                append("Sample missing pairs:\n")
                append(sampleLines)
                append(moreJps)
            }
        }

        private const val SAMPLE_JPS = 3
        private const val SAMPLE_PAIRS = 3
        private const val SAMPLE_QUAYS = 10

        // Stop adding to the sampled `missing` list past this size. Bounds the transient memory
        // cost of formatIntegrityError on pathological inputs (100k missing pairs ≈ 12 MB list +
        // transient maps/sets); the headline counts use a separate counter so they stay accurate.
        private const val HARD_CAP_MISSING_PAIRS = 5_000

        // Cap the list carried on the exception so a pathological failure doesn't retain a
        // multi-MB list across the failure-handling path (logging, PubSub publish, etc.).
        private const val MAX_REPORTED_MISSING_PAIRS = 1000

        /**
         * Find consecutive stop pairs that appear in JPs of more than one transport mode.
         * `routePairs` picks one mode (the JP that introduced the pair) to send to OSRM, so the
         * resulting geometry is wrong for the other mode's JP. Returns `pairKey → set of modes`.
         */
        fun detectPairModeConflicts(
            sequencesByJp: Map<String, List<String>>,
            modeByJp: Map<String, TransportMode?>,
        ): Map<String, Set<String>> {
            val pairModes = mutableMapOf<String, MutableSet<String>>()
            for ((jpId, seq) in sequencesByJp) {
                val mode = modeByJp[jpId]?.name ?: continue
                for (i in 0 until seq.size - 1) {
                    pairModes.getOrPut("${seq[i]}_${seq[i + 1]}") { mutableSetOf() }.add(mode)
                }
            }
            return pairModes.filterValues { it.size > 1 }
        }

        /** Single canonical formatter for the mode-conflict WARN — keep both call sites in sync. */
        fun warnOnPairModeConflicts(
            logger: org.slf4j.Logger,
            conflicts: Map<String, Set<String>>,
        ) {
            if (conflicts.isEmpty()) return
            val sample =
                conflicts.entries.take(3).joinToString("; ") { (pairKey, modes) ->
                    "$pairKey [${modes.sorted().joinToString("/")}]"
                }
            val more = if (conflicts.size > 3) " (+${conflicts.size - 3} more)" else ""
            logger.warn(
                "{} stop pair(s) referenced under multiple transport modes — OSRM geometry will follow the first-seen mode and may be wrong for the others. Sample: {}{}",
                conflicts.size,
                sample,
                more,
            )
        }

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

        /** Default sanity limit (5000 km) — override via `servicelinker.max-route-distance-meters`. */
        private const val MAX_ROUTE_DISTANCE_METERS_DEFAULT: Long = 5_000_000

        private const val MAX_DETOUR_RATIO = 10.0

        private const val MIN_DETOUR_CHECK_DIRECT_METERS = 500.0

        /** Matches `gml` when used as an XML namespace prefix — in declarations,
         *  element tags, or namespace-qualified attributes. Single-pass replacement. */
        private val GML_PREFIX_PATTERN = Regex("""(?<=xmlns:|</?| )gml(?=[:=])""")
    }
}

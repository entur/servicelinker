package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory


@Service
class NetexProcessor(
    private val osrmService: OSRMService,
    private val staxTransformer: StaxNetexTransformer
) {
    private val logger = LoggerFactory.getLogger(NetexProcessor::class.java)

    suspend fun processNetexFile(
        inputStream: InputStream,
        externalStopPoints: Map<String, StopPoint> = emptyMap(),
        modesFilter: Set<String>? = null,
        limit: Int? = null
    ): Pair<ByteArray, Int> {
        logger.info("Starting NeTEx file processing")
        @Suppress("BlockingMethodInNonBlockingContext") // Always a ByteArrayInputStream in practice
        val inputBytes = inputStream.readAllBytes()
        val document = parseXmlDocument(ByteArrayInputStream(inputBytes))

        val localStops = extractStopPoints(document).associateBy { it.id }

        // Merge: local takes priority over external (which may include NSR-enriched stops)
        val stopPointsMap = externalStopPoints + localStops

        val existingServiceLinks = extractExistingServiceLinks(document)
        val journeyPatterns = extractJourneyPatterns(document)

        // Collect unique consecutive pairs from all JourneyPatterns
        val jpModeMap = buildJpModeMap(document)
        val neededPairs = mutableMapOf<String, StationPair>()
        for ((jpElement, stopRefIds) in journeyPatterns) {
            val jpMode = jpModeMap[jpElement.getAttribute("id")]
            for (i in 0 until stopRefIds.size - 1) {
                val fromRef = stopRefIds[i]
                val toRef = stopRefIds[i + 1]
                val pairKey = "${fromRef}_${toRef}"
                if (!existingServiceLinks.containsKey(pairKey) && !neededPairs.containsKey(pairKey)) {
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
        val allServiceLinks = existingServiceLinks.toMutableMap()

        val routedPairs = routePairs(neededPairs, modesFilter, limit)
        val newServiceLinkData = routedPairs.map { (pairKey, pairAndRoute) ->
            val data = createServiceLinkData(pairAndRoute.first, pairAndRoute.second)
            allServiceLinks[pairKey] = data.id
            pairKey to data
        }
        val addedCount = newServiceLinkData.size

        if (addedCount == 0) {
            logger.info("NeTEx processing completed. No service links to add")
            return Pair(inputBytes, 0)
        }

        val plan = buildInjectionPlan(
            newServiceLinkData = newServiceLinkData,
            allServiceLinks = allServiceLinks,
            jpStopSequences = journeyPatterns.associate { (jp, refs) -> jp.getAttribute("id") to refs },
            existingSlijpEntries = collectExistingSlijpEntries(document),
            declaredStopIds = extractScheduledStopPointIdsAndVersions(document).keys,
            needsGmlNamespace = true
        )

        val modifiedXml = staxTransformer.transform(inputBytes, plan)
        logger.info("NeTEx processing completed. Added $addedCount service links")
        return Pair(modifiedXml, addedCount)
    }

    fun parseXmlDocument(inputStream: InputStream): Document {
        val builder = documentBuilderFactory.newDocumentBuilder()
        return builder.parse(inputStream)
    }

    fun extractStopAssignments(document: Document): Map<String, String> {
        val assignments = mutableMapOf<String, String>()

        val passengerStopAssignments = document.getElementsByTagName("PassengerStopAssignment")
        for (i in 0 until passengerStopAssignments.length) {
            val element = passengerStopAssignments.item(i) as Element
            val stopPointRef = (element.getElementsByTagName("ScheduledStopPointRef").item(0) as? Element)
                ?.getAttribute("ref")
            val quayRef = (element.getElementsByTagName("QuayRef").item(0) as? Element)
                ?.getAttribute("ref")

            if (!stopPointRef.isNullOrBlank() && !quayRef.isNullOrBlank()) {
                assignments[stopPointRef] = quayRef
            }
        }

        if (assignments.isNotEmpty()) {
            logger.info("Extracted ${assignments.size} stop assignments (ScheduledStopPoint → Quay)")
        }
        return assignments
    }

    fun extractStopPoints(document: Document): List<StopPoint> {
        val stopPoints = mutableListOf<StopPoint>()

        val scheduledStopPoints = document.getElementsByTagName("ScheduledStopPoint")
        for (i in 0 until scheduledStopPoints.length) {
            val element = scheduledStopPoints.item(i) as Element
            val stopPoint = parseStopPoint(element)
            if (stopPoint != null) {
                stopPoints.add(stopPoint)
            }
        }

        val stopPlaces = document.getElementsByTagName("StopPlace")
        for (i in 0 until stopPlaces.length) {
            val element = stopPlaces.item(i) as Element
            val stopPoint = parseStopPlace(element)
            if (stopPoint != null) {
                stopPoints.add(stopPoint)
            }
        }

        return stopPoints
    }

    private fun parseStopPoint(element: Element): StopPoint? {
        return try {
            val id = element.getAttribute("id")
            val version = element.getAttribute("version")
            val name = getElementText(element, "Name") ?: getElementText(element, "ShortName")
            val location = element.getElementsByTagName("Location").item(0) as? Element

            if (location != null) {
                val longitude = getElementText(location, "Longitude")?.toBigDecimalOrNull()
                val latitude = getElementText(location, "Latitude")?.toBigDecimalOrNull()

                if (longitude != null && latitude != null) {
                    val coordinate = Coordinate(latitude, longitude)
                    val transportMode = extractTransportMode(element)
                    return StopPoint(id, name, coordinate, transportMode, version)
                }
            }
            null
        } catch (e: Exception) {
            logger.warn("Failed to parse stop point from element", e)
            null
        }
    }

    private fun parseStopPlace(element: Element): StopPoint? {
        return try {
            val id = element.getAttribute("id")
            val version = element.getAttribute("version")
            val name = getElementText(element, "Name")
            val centroid = element.getElementsByTagName("Centroid").item(0) as? Element

            if (centroid != null) {
                val location = centroid.getElementsByTagName("Location").item(0) as? Element
                if (location != null) {
                    val longitude = getElementText(location, "Longitude")?.toBigDecimalOrNull()
                    val latitude = getElementText(location, "Latitude")?.toBigDecimalOrNull()

                    if (longitude != null && latitude != null) {
                        val coordinate = Coordinate(latitude, longitude)
                        val transportMode = extractTransportMode(element)
                        return StopPoint(id, name, coordinate, transportMode, version)
                    }
                }
            }
            null
        } catch (e: Exception) {
            logger.warn("Failed to parse stop place from element", e)
            null
        }
    }

    fun extractExistingServiceLinks(document: Document): Map<String, String> {
        val existingLinks = mutableMapOf<String, String>()

        val serviceLinks = document.getElementsByTagName("ServiceLink")
        for (i in 0 until serviceLinks.length) {
            val element = serviceLinks.item(i) as Element
            val serviceLinkId = element.getAttribute("id")
            val fromRefElement = element.getElementsByTagName("FromPointRef").item(0) as? Element
            val toRefElement = element.getElementsByTagName("ToPointRef").item(0) as? Element
            val fromRef = fromRefElement?.getAttribute("ref")
            val toRef = toRefElement?.getAttribute("ref")

            if (!fromRef.isNullOrBlank() && !toRef.isNullOrBlank()) {
                existingLinks["${fromRef}_${toRef}"] = serviceLinkId
            }
        }

        return existingLinks
    }

    private fun extractJourneyPatterns(document: Document): List<Pair<Element, List<String>>> {
        val result = mutableListOf<Pair<Element, List<String>>>()

        val journeyPatterns = document.getElementsByTagName("JourneyPattern")
        for (i in 0 until journeyPatterns.length) {
            val jpElement = journeyPatterns.item(i) as Element
            val stopPointsInSequence = jpElement.getElementsByTagName("StopPointInJourneyPattern")

            data class OrderedRef(val order: Int, val ref: String)

            val orderedRefs = mutableListOf<OrderedRef>()
            for (j in 0 until stopPointsInSequence.length) {
                val spijp = stopPointsInSequence.item(j) as Element
                val order = spijp.getAttribute("order").toIntOrNull() ?: (j + 1)
                val refElement = spijp.getElementsByTagName("ScheduledStopPointRef").item(0) as? Element
                val ref = refElement?.getAttribute("ref")
                if (ref != null) {
                    orderedRefs.add(OrderedRef(order, ref))
                }
            }

            val stopRefIds = orderedRefs.sortedBy { it.order }.map { it.ref }
            if (stopRefIds.size >= 2) {
                result.add(jpElement to stopRefIds)
            }
        }

        return result
    }

    // --- Public helpers for multi-file ZIP processing ---

    /** Return all ScheduledStopPoint IDs and their versions declared in the document. */
    fun extractScheduledStopPointIdsAndVersions(document: Document): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        val elements = document.getElementsByTagName("ScheduledStopPoint")
        for (i in 0 until elements.length) {
            val el = elements.item(i) as Element
            val id = el.getAttribute("id")
            val version = el.getAttribute("version").ifBlank { "1" }
            if (id.isNotBlank()) ids[id] = version
        }
        return ids
    }

    /** Collect raw JP pair refs (stop IDs + transport mode) without requiring enriched coordinates. */
    fun collectJpPairRefs(document: Document): Map<String, Triple<String, String, TransportMode?>> {
        val journeyPatterns = extractJourneyPatterns(document)
        val jpModeMap = buildJpModeMap(document)
        val result = mutableMapOf<String, Triple<String, String, TransportMode?>>()
        for ((jpElement, stopRefIds) in journeyPatterns) {
            val jpMode = jpModeMap[jpElement.getAttribute("id")]
            for (i in 0 until stopRefIds.size - 1) {
                val fromRef = stopRefIds[i]
                val toRef = stopRefIds[i + 1]
                result.putIfAbsent("${fromRef}_${toRef}", Triple(fromRef, toRef, jpMode))
            }
        }
        return result
    }

    /** Extract ordered stop sequences per JourneyPattern. Key = JP id. */
    fun collectJpStopSequences(document: Document): Map<String, List<String>> =
        extractJourneyPatterns(document).associate { (jp, refs) -> jp.getAttribute("id") to refs }

    /** Extract existing ServiceLinkInJourneyPattern metadata per JP. */
    fun collectExistingSlijpEntries(document: Document): Map<String, Map<String, ExistingSlijpInfo>> {
        val result = mutableMapOf<String, Map<String, ExistingSlijpInfo>>()
        val journeyPatterns = document.getElementsByTagName("JourneyPattern")
        for (i in 0 until journeyPatterns.length) {
            val jp = journeyPatterns.item(i) as Element
            val jpId = jp.getAttribute("id")

            val linksInSeq = jp.getElementsByTagName("linksInSequence").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) }.firstOrNull { it.parentNode == jp } as? Element
            } ?: continue

            val slijps = linksInSeq.getElementsByTagName("ServiceLinkInJourneyPattern")
            val entries = mutableMapOf<String, ExistingSlijpInfo>()
            for (j in 0 until slijps.length) {
                val slijp = slijps.item(j) as Element
                val ref = (slijp.getElementsByTagName("ServiceLinkRef").item(0) as? Element) ?: continue
                val serviceLinkRef = ref.getAttribute("ref")
                val refVersion = ref.getAttribute("version").takeIf { it.isNotBlank() }
                entries[serviceLinkRef] = ExistingSlijpInfo(
                    id = slijp.getAttribute("id"),
                    version = slijp.getAttribute("version").ifBlank { "1" },
                    serviceLinkRefVersion = refVersion
                )
            }
            if (entries.isNotEmpty()) {
                result[jpId] = entries
            }
        }
        return result
    }

    /** Route stop pairs via OSRM, applying mode filter and distance/limit constraints. */
    suspend fun routePairs(
        pairs: Map<String, StationPair>,
        modesFilter: Set<String>?,
        limit: Int?
    ): LinkedHashMap<String, Pair<StationPair, OSRMRoute?>> {
        val result = linkedMapOf<String, Pair<StationPair, OSRMRoute?>>()
        var remaining = limit
        for ((pairKey, pair) in pairs) {
            if (remaining != null && remaining <= 0) break
            if (modesFilter != null && !modesFilter.contains(pair.transportMode?.name)) {
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: mode ${pair.transportMode} not in filter $modesFilter")
                continue
            }
            val route = try {
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
            result[pairKey] = pair to route
            if (remaining != null) remaining = (remaining - 1).coerceAtLeast(0)
        }
        return result
    }

    /** Apply a pre-built InjectionPlan to raw XML bytes via StAX streaming. */
    fun applyPlan(input: ByteArray, plan: InjectionPlan): ByteArray =
        staxTransformer.transform(input, plan)

    /** Create a ServiceLinkData object from a routed stop pair. Pure data, no DOM. */
    fun createServiceLinkData(pair: StationPair, route: OSRMRoute?): ServiceLinkData {
        val (codespace, fromId) = splitNetexId(pair.from.id)
        val (_, toId) = splitNetexId(pair.to.id)
        val timestamp = TIMESTAMP_FORMAT.format(java.time.LocalDateTime.now())
        val serviceLinkId = computeServiceLinkId(pair)

        val fromCoord = listOf(pair.from.coordinate.longitude, pair.from.coordinate.latitude)
        val toCoord = listOf(pair.to.coordinate.longitude, pair.to.coordinate.latitude)
        val coordinates = if (route != null) {
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
            projectionId = "$codespace:LinkSequenceProjection:${timestamp}_${fromId}_${toId}",
            gmlLineStringId = "lsp_${fromId}_${toId}",
            posList = posListString,
            fromPointRef = pair.from.id,
            fromPointVersion = pair.from.version ?: "1",
            toPointRef = pair.to.id,
            toPointVersion = pair.to.version ?: "1"
        )
    }

    /** Build an InjectionPlan that captures all modifications for a single file. */
    fun buildInjectionPlan(
        newServiceLinkData: List<Pair<String, ServiceLinkData>>,
        allServiceLinks: Map<String, String>,
        jpStopSequences: Map<String, List<String>>,
        existingSlijpEntries: Map<String, Map<String, ExistingSlijpInfo>>,
        declaredStopIds: Set<String>,
        needsGmlNamespace: Boolean
    ): InjectionPlan {
        // Stub stops: referenced by new ServiceLinks but not declared in this file
        val referencedStops = mutableMapOf<String, String>()
        for ((_, data) in newServiceLinkData) {
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
                val pairKey = "${fromRef}_${toRef}"
                val serviceLinkId = allServiceLinks[pairKey] ?: continue

                val existingEntry = existing[serviceLinkId]
                if (existingEntry != null) {
                    entries.add(
                        SlijpEntry(
                            id = existingEntry.id,
                            version = existingEntry.version,
                            order = order,
                            serviceLinkRef = serviceLinkId,
                            serviceLinkRefVersion = existingEntry.serviceLinkRefVersion
                        )
                    )
                } else {
                    val fromLocalId = fromRef.split(":").lastOrNull() ?: fromRef
                    val toLocalId = toRef.split(":").lastOrNull() ?: toRef
                    val slijpId = "$codespace:ServiceLinkInJourneyPattern:${jpLocalId}_${fromLocalId}_to_${toLocalId}"
                    entries.add(
                        SlijpEntry(
                            id = slijpId,
                            version = "1",
                            order = order,
                            serviceLinkRef = serviceLinkId,
                            serviceLinkRefVersion = null
                        )
                    )
                }
                order++
            }
            if (entries.isNotEmpty()) {
                jpLinks[jpId] = entries
            }
        }

        return InjectionPlan(
            newServiceLinks = newServiceLinkData.map { it.second },
            stubScheduledStopPoints = stubStops,
            journeyPatternLinks = jpLinks,
            needsGmlNamespace = needsGmlNamespace
        )
    }

    /** Build an InjectionPlan for a ZIP file entry using pre-routed data. */
    fun buildZipInjectionPlan(
        newServiceLinksForThisDoc: List<Pair<String, Pair<StationPair, OSRMRoute?>>>,
        allServiceLinks: Map<String, String>,
        jpStopSequences: Map<String, List<String>>,
        existingSlijpEntries: Map<String, Map<String, ExistingSlijpInfo>>,
        declaredStopIds: Set<String>
    ): InjectionPlan {
        val newData = newServiceLinksForThisDoc.map { (pairKey, pairAndRoute) ->
            val (pair, route) = pairAndRoute
            pairKey to createServiceLinkData(pair, route)
        }
        return buildInjectionPlan(
            newData, allServiceLinks, jpStopSequences,
            existingSlijpEntries, declaredStopIds,
            needsGmlNamespace = newData.isNotEmpty()
        )
    }

    // --- Private helpers ---

    private fun buildJpModeMap(document: Document): Map<String, TransportMode?> {
        // lineId → TransportMode
        val lineModes = mutableMapOf<String, TransportMode>()
        val lines = document.getElementsByTagName("Line")
        for (i in 0 until lines.length) {
            val line = lines.item(i) as Element
            val mode = extractTransportMode(line)
            if (mode != null) lineModes[line.getAttribute("id")] = mode
        }

        // routeId → lineId
        val routeToLine = mutableMapOf<String, String>()
        val routes = document.getElementsByTagName("Route")
        for (i in 0 until routes.length) {
            val route = routes.item(i) as Element
            val lineRef = (route.getElementsByTagName("LineRef").item(0) as? Element)?.getAttribute("ref")
            if (lineRef != null) routeToLine[route.getAttribute("id")] = lineRef
        }

        // jpId → TransportMode, resolved via: direct TransportMode > LineRef > RouteRef → Route → LineRef
        val result = mutableMapOf<String, TransportMode?>()
        val jps = document.getElementsByTagName("JourneyPattern")
        for (i in 0 until jps.length) {
            val jp = jps.item(i) as Element
            val jpId = jp.getAttribute("id")

            val directMode = extractTransportMode(jp)
            if (directMode != null) { result[jpId] = directMode; continue }

            val lineRef = (jp.getElementsByTagName("LineRef").item(0) as? Element)?.getAttribute("ref")
            if (lineRef != null) { result[jpId] = lineModes[lineRef]; continue }

            val routeRef = (jp.getElementsByTagName("RouteRef").item(0) as? Element)?.getAttribute("ref")
            val lineId = if (routeRef != null) routeToLine[routeRef] else null
            result[jpId] = if (lineId != null) lineModes[lineId] else null
        }
        return result
    }

    private fun extractTransportMode(element: Element): TransportMode? {
        val transportModeText = getElementText(element, "TransportMode")
            ?: getElementText(element, "VehicleMode")
            ?: return null

        return when (transportModeText.uppercase()) {
            "BUS" -> TransportMode.BUS
            "TRAM" -> TransportMode.TRAM
            "RAIL" -> TransportMode.RAIL
            "METRO" -> TransportMode.METRO
            "FERRY" -> TransportMode.FERRY
            "WATER" -> TransportMode.WATER
            "COACH" -> TransportMode.COACH
            else -> TransportMode.UNKNOWN
        }
    }

    private fun getElementText(parent: Element, tagName: String): String? {
        val elements = parent.getElementsByTagName(tagName)
        return if (elements.length > 0) {
            elements.item(0).textContent?.trim()
        } else null
    }

    companion object {
        // Created per-call via newDocumentBuilder() — the factory is not guaranteed thread-safe
        private val documentBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }

        private val TIMESTAMP_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

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
    }
}

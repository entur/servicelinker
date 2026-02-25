package no.entur.servicelinker.service

import kotlinx.coroutines.CancellationException
import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.OSRMRoute
import no.entur.servicelinker.model.StationPair
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


@Service
class NetexProcessor(
    private val osrmService: OSRMService
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
        var addedCount = 0

        // Build new ServiceLink elements, but only touch the document if we actually add any
        val newServiceLinkElements = mutableListOf<Pair<String, Element>>()
        for ((pairKey, pair) in neededPairs) {
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
                logger.debug("Skipping pair ${pair.from.id} → ${pair.to.id}: route distance ${route.distance}m exceeds max")
                continue
            }
            val serviceLinkElement = createServiceLinkElement(document, pair, route)
            allServiceLinks[pairKey] = serviceLinkElement.getAttribute("id")
            newServiceLinkElements.add(pairKey to serviceLinkElement)
            addedCount++
            if (limit != null && addedCount >= limit) {
                logger.info("Reached ServiceLink limit ($limit), stopping early")
                break
            }
        }

        if (addedCount == 0) {
            logger.info("NeTEx processing completed. No service links to add")
            return Pair(inputBytes, 0)
        }

        addServiceLinksSection(document, newServiceLinkElements)

        // Add linksInSequence to each JourneyPattern
        for ((jpElement, stopRefIds) in journeyPatterns) {
            addLinksInSequence(document, jpElement, stopRefIds, allServiceLinks)
        }

        ensureGmlNamespace(document)
        val modifiedXml = documentToByteArray(document)
        logger.info("NeTEx processing completed. Added $addedCount service links")
        return Pair(modifiedXml, addedCount)
    }

    fun parseXmlDocument(inputStream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
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

    private fun addLinksInSequence(
        document: Document,
        jpElement: Element,
        stopRefIds: List<String>,
        allServiceLinks: Map<String, String>
    ) {
        val codespace = jpElement.getAttribute("id").split(":").firstOrNull() ?: return

        // Index existing ServiceLinkInJourneyPattern entries by their ServiceLinkRef
        val existingLinksInSeqElement = jpElement.getElementsByTagName("linksInSequence").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) }.firstOrNull { it.parentNode == jpElement } as? Element
        }
        val existingByServiceLinkId = mutableMapOf<String, Element>()
        if (existingLinksInSeqElement != null) {
            val slijps = existingLinksInSeqElement.getElementsByTagName("ServiceLinkInJourneyPattern")
            for (i in 0 until slijps.length) {
                val slijp = slijps.item(i) as Element
                val ref = (slijp.getElementsByTagName("ServiceLinkRef").item(0) as? Element)
                    ?.getAttribute("ref")
                if (ref != null) {
                    existingByServiceLinkId[ref] = slijp
                }
            }
        }

        // Build new linksInSequence: reuse existing entries, create new ones for missing pairs
        val linksInSequence = document.createElement("linksInSequence")
        var order = 1

        for (i in 0 until stopRefIds.size - 1) {
            val fromRef = stopRefIds[i]
            val toRef = stopRefIds[i + 1]
            val pairKey = "${fromRef}_${toRef}"
            val serviceLinkId = allServiceLinks[pairKey] ?: continue

            val existing = existingByServiceLinkId[serviceLinkId]
            if (existing != null) {
                // Reuse existing entry, just update the order
                val imported = document.importNode(existing, true) as Element
                imported.setAttribute("order", order.toString())
                linksInSequence.appendChild(imported)
            } else {
                val fromLocalId = fromRef.split(":").lastOrNull() ?: fromRef
                val toLocalId = toRef.split(":").lastOrNull() ?: toRef
                val slijpId = "$codespace:ServiceLinkInJourneyPattern:${fromLocalId}_to_${toLocalId}"

                val slijpElement = document.createElement("ServiceLinkInJourneyPattern")
                slijpElement.setAttribute("id", slijpId)
                slijpElement.setAttribute("version", "1")
                slijpElement.setAttribute("order", order.toString())

                val serviceLinkRef = document.createElement("ServiceLinkRef")
                serviceLinkRef.setAttribute("ref", serviceLinkId)
                serviceLinkRef.setAttribute("version", "1")
                slijpElement.appendChild(serviceLinkRef)

                linksInSequence.appendChild(slijpElement)
            }
            order++
        }

        // Replace old linksInSequence with the merged one
        if (existingLinksInSeqElement != null) {
            jpElement.removeChild(existingLinksInSeqElement)
        }
        if (linksInSequence.hasChildNodes()) {
            jpElement.appendChild(linksInSequence)
        }
    }

    private fun createServiceLinkElement(document: Document, pair: StationPair, route: OSRMRoute?): Element {
        val fromParts = pair.from.id.split(":")
        val toParts = pair.to.id.split(":")
        val tariffZone = fromParts.first()
        val fromId = fromParts.lastOrNull() ?: pair.from.id
        val toId = toParts.lastOrNull() ?: pair.to.id
        val version = "1"
        val timestamp =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now())
        val serviceLinkId = computeServiceLinkId(pair)
        val linkSequenceProjectionId = "$tariffZone:LinkSequenceProjection:${timestamp}_${fromId}_${toId}"

        val serviceLinkElement = document.createElement("ServiceLink")
        serviceLinkElement.setAttribute("id", serviceLinkId)
        serviceLinkElement.setAttribute("version", version)

        if (route?.distance != null) {
            val distanceElement = document.createElement("Distance")
            distanceElement.textContent = route.distance.toInt().toString()
            serviceLinkElement.appendChild(distanceElement)
        }

        val projectionsElement = document.createElement("projections")
        val linkSequenceProjection = document.createElement("LinkSequenceProjection")
        linkSequenceProjection.setAttribute("id", linkSequenceProjectionId)
        linkSequenceProjection.setAttribute("version", "1")
        val gmlLineString = document.createElementNS(GML_NS, "gml:LineString")
        gmlLineString.setAttribute("srsName", SRS_NAME_WGS84)
        gmlLineString.setAttributeNS(GML_NS, "gml:id", "lsp_${fromId}_${toId}")
        val coordinates = route?.coordinates ?: listOf(
            listOf(pair.from.coordinate.longitude.toDouble(), pair.from.coordinate.latitude.toDouble()),
            listOf(pair.to.coordinate.longitude.toDouble(), pair.to.coordinate.latitude.toDouble())
        )
        val posListString = coordinates.joinToString(" ") { coord -> "${coord[1]} ${coord[0]}" }
        val posList = document.createElementNS(GML_NS, "gml:posList")
        posList.textContent = posListString
        gmlLineString.appendChild(posList)
        linkSequenceProjection.appendChild(gmlLineString)
        projectionsElement.appendChild(linkSequenceProjection)
        serviceLinkElement.appendChild(projectionsElement)

        val fromPointRef = document.createElement("FromPointRef")
        fromPointRef.setAttribute("ref", pair.from.id)
        fromPointRef.setAttribute("version", version)
        serviceLinkElement.appendChild(fromPointRef)

        val toPointRef = document.createElement("ToPointRef")
        toPointRef.setAttribute("ref", pair.to.id)
        toPointRef.setAttribute("version", version)
        serviceLinkElement.appendChild(toPointRef)

        return serviceLinkElement
    }

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

    // --- Public helpers for multi-file ZIP processing ---

    /** Collect raw JP pair refs (stop IDs + transport mode) without requiring enriched coordinates.
     *  Used by the ZIP planning pass so stop-collection and JP-scanning can share one ZIP read. */
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

    /** Collect pairs needing new ServiceLinks from this document's JourneyPatterns. */
    fun findNeededPairs(
        document: Document,
        allStopPoints: Map<String, StopPoint>,
        existingServiceLinks: Map<String, String>
    ): Map<String, StationPair> {
        val localStops = extractStopPoints(document).associateBy { it.id }
        val stopPointsMap = allStopPoints + localStops
        val journeyPatterns = extractJourneyPatterns(document)
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
                    if (from != null && to != null) neededPairs[pairKey] = StationPair(from, to, jpMode)
                }
            }
        }
        return neededPairs
    }

    /** Inject pre-routed ServiceLinks and linksInSequence into a document.
     *  Used by multi-file ZIP processing where OSRM routing is done globally. */
    fun injectLinks(
        inputBytes: ByteArray,
        newServiceLinksForThisDoc: List<Pair<String, Pair<StationPair, OSRMRoute?>>>,
        allServiceLinks: Map<String, String>
    ): Pair<ByteArray, Int> {
        val document = parseXmlDocument(ByteArrayInputStream(inputBytes))
        val journeyPatterns = extractJourneyPatterns(document)
        val hasNewServiceLinks = newServiceLinksForThisDoc.isNotEmpty()
        val hasJourneyPatterns = journeyPatterns.isNotEmpty()

        if (!hasNewServiceLinks && (!hasJourneyPatterns || allServiceLinks.isEmpty())) {
            return Pair(inputBytes, 0)
        }

        if (hasNewServiceLinks) {
            val newServiceLinkElements = newServiceLinksForThisDoc.map { (pairKey, pairAndRoute) ->
                val (pair, route) = pairAndRoute
                pairKey to createServiceLinkElement(document, pair, route)
            }
            addServiceLinksSection(document, newServiceLinkElements)
            ensureGmlNamespace(document)
            logger.info("Injected ${newServiceLinkElements.size} new ServiceLink(s)")
        }

        if (hasJourneyPatterns) {
            for ((jpElement, stopRefIds) in journeyPatterns) {
                addLinksInSequence(document, jpElement, stopRefIds, allServiceLinks)
            }
        }

        return Pair(documentToByteArray(document), newServiceLinksForThisDoc.size)
    }

    // --- Private helpers ---

    private fun addServiceLinksSection(document: Document, newServiceLinkElements: List<Pair<String, Element>>) {
        var serviceLinksSection = document.getElementsByTagName("serviceLinks").item(0) as? Element
        if (serviceLinksSection == null) {
            val serviceFrame = document.getElementsByTagName("ServiceFrame").item(0) as? Element
                ?: document.getElementsByTagName("CompositeFrame").item(0) as? Element
                ?: document.documentElement
            serviceLinksSection = document.createElement("serviceLinks")
            // serviceLinks must precede journeyPatterns and other later elements in the NeTEx schema
            val insertionPoint = serviceFrame.childNodes.let { nodes ->
                (0 until nodes.length).asSequence().map { nodes.item(it) }
                    .firstOrNull { it is Element && it.localName in AFTER_SERVICE_LINKS }
            }
            if (insertionPoint != null) {
                serviceFrame.insertBefore(serviceLinksSection, insertionPoint)
            } else {
                serviceFrame.appendChild(serviceLinksSection)
            }
        }
        for ((_, element) in newServiceLinkElements) {
            serviceLinksSection.appendChild(element)
        }
    }

    private fun ensureGmlNamespace(document: Document) {
        val root = document.documentElement
        val existing = root.getAttribute("xmlns:gml")
        if (existing != GML_NS) {
            root.setAttribute("xmlns:gml", GML_NS)
        }
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

    private fun documentToByteArray(document: Document): ByteArray {
        val transformer = TransformerFactory.newInstance().newTransformer()
        val source = DOMSource(document)
        val outputStream = ByteArrayOutputStream()
        val result = StreamResult(outputStream)
        transformer.transform(source, result)
        return outputStream.toByteArray()
    }

    companion object {
        const val SRS_NAME_WGS84: String = "EPSG:4326"

        fun computeServiceLinkId(pair: StationPair): String {
            val fromParts = pair.from.id.split(":")
            val toParts = pair.to.id.split(":")
            val tariffZone = fromParts.first()
            val fromId = fromParts.lastOrNull() ?: pair.from.id
            val toId = toParts.lastOrNull() ?: pair.to.id
            return "$tariffZone:ServiceLink:$fromId-$toId"
        }
        const val MAX_ROUTE_DISTANCE_METERS = 5_000_000 // 5000 km — sanity limit
        const val GML_NS = "http://www.opengis.net/gml/3.2"

        // NeTEx ServiceFrame elements that must appear after <serviceLinks> in schema order
        val AFTER_SERVICE_LINKS = setOf(
            "journeyPatterns", "stopAssignments", "connections",
            "timingPoints", "timingLinks", "tariffZones",
            "routingConstraintZones", "transferRestrictions", "serviceExclusions",
            "timeDemandTypes", "timeDemandTypeAssignments", "timingLinkGroups",
            "notices", "noticeAssignments", "logicalDisplays",
            "displayAssignments", "passengerInformationEquipments"
        )
    }
}

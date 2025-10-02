package no.entur.servicelinker.service

import no.entur.servicelinker.model.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Service
class NetexProcessor(
    @Autowired private val osrmService: OSRMService
) {
    private val logger = LoggerFactory.getLogger(NetexProcessor::class.java)

    suspend fun processNetexFile(inputStream: InputStream): Pair<ByteArray, Int> {
        logger.info("Starting NeTEx file processing")

        val document = parseXmlDocument(inputStream)
        val stopPoints = extractStopPoints(document)
        val existingServiceLinks = extractExistingServiceLinks(document)

        logger.info("Found ${stopPoints.size} stop points and ${existingServiceLinks.size} existing service links")

        val stationPairs = generateStationPairs(stopPoints, existingServiceLinks)
        logger.info("Generated ${stationPairs.size} station pairs that need service links")

        val addedCount = addServiceLinksToDocument(document, stationPairs)

        val modifiedXml = documentToByteArray(document)

        logger.info("NeTEx processing completed. Added $addedCount service links")
        return Pair(modifiedXml, addedCount)
    }

    private fun parseXmlDocument(inputStream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(inputStream)
    }

    private fun extractStopPoints(document: Document): List<StopPoint> {
        val stopPoints = mutableListOf<StopPoint>()

        // Extract ScheduledStopPoints
        val scheduledStopPoints = document.getElementsByTagName("ScheduledStopPoint")
        for (i in 0 until scheduledStopPoints.length) {
            val element = scheduledStopPoints.item(i) as Element
            val stopPoint = parseStopPoint(element)
            if (stopPoint != null) {
                stopPoints.add(stopPoint)
            }
        }

        // Extract StopPlaces if present
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

    private fun extractExistingServiceLinks(document: Document): Set<String> {
        val existingLinks = mutableSetOf<String>()

        val serviceLinks = document.getElementsByTagName("ServiceLink")
        for (i in 0 until serviceLinks.length) {
            val element = serviceLinks.item(i) as Element
            val fromRef = getElementText(element, "FromPointRef")
            val toRef = getElementText(element, "ToPointRef")

            if (fromRef != null && toRef != null) {
                existingLinks.add("${fromRef}_${toRef}")
            }
        }

        return existingLinks
    }

    private suspend fun generateStationPairs(
        stopPoints: List<StopPoint>,
        existingServiceLinks: Set<String>
    ): List<StationPair> {
        val pairs = mutableListOf<StationPair>()

        for (i in stopPoints.indices) {
            for (j in i + 1 until stopPoints.size) {
                val from = stopPoints[i]
                val to = stopPoints[j]

                val pairKey = "${from.id}_${to.id}"
                val reversePairKey = "${to.id}_${from.id}"

                if (!existingServiceLinks.contains(pairKey) && !existingServiceLinks.contains(reversePairKey)) {
                    // Try to get OSRM route to check if connection is possible
                    val route = try {
                        osrmService.calculateRoute(from.coordinate, to.coordinate, from.transportMode)
                    } catch (e: Exception) {
                        null
                    }

                    // Only add pairs where OSRM can find a route (and distance is reasonable)
                    if (route != null && route.distance <= 50000) {
                        pairs.add(StationPair(from, to))
                    }
                }
            }
        }

        return pairs
    }


    private suspend fun addServiceLinksToDocument(document: Document, stationPairs: List<StationPair>): Int {
        var serviceLinksSection = document.getElementsByTagName("serviceLinks").item(0) as? Element

        if (serviceLinksSection == null) {
            val commonFrame = document.getElementsByTagName("ServiceFrame").item(0) as? Element
                ?: document.getElementsByTagName("CompositeFrame").item(0) as? Element
                ?: document.documentElement

            serviceLinksSection = document.createElement("serviceLinks")
            commonFrame.appendChild(serviceLinksSection)
        }

        var addedCount = 0

        for (pair in stationPairs) {
            val route = try {
                osrmService.calculateRoute(pair.from.coordinate, pair.to.coordinate, pair.from.transportMode)
            } catch (e: Exception) {
                logger.warn("OSRM route failed for ${pair.from.id} to ${pair.to.id}, using straight-line distance", e)
                null
            }

            val serviceLinkElement = createServiceLinkElementWithDistance(document, pair, route?.distance?.toInt())
            serviceLinksSection.appendChild(serviceLinkElement)
            addedCount++
        }

        return addedCount
    }

    private fun createServiceLinkElementWithDistance(document: Document, pair: StationPair, osrmDistance: Int?): Element {
        val serviceLinkId = pair.generateServiceLinkId("LS_")

        val serviceLinkElement = document.createElement("ServiceLink")
        serviceLinkElement.setAttribute("id", serviceLinkId)
        serviceLinkElement.setAttribute("version", "1")

        val fromPointRef = document.createElement("FromPointRef")
        fromPointRef.setAttribute("ref", pair.from.id)
        serviceLinkElement.appendChild(fromPointRef)

        val toPointRef = document.createElement("ToPointRef")
        toPointRef.setAttribute("ref", pair.to.id)
        serviceLinkElement.appendChild(toPointRef)

        if (osrmDistance != null) {
            val distanceElement = document.createElement("Distance")
            distanceElement.textContent = osrmDistance.toString()
            serviceLinkElement.appendChild(distanceElement)
        }

        return serviceLinkElement
    }


    private fun createServiceLinkElementWithRoute(document: Document, pair: StationPair, route: OSRMRoute?): Element {
        val fromParts = pair.from.id.split(":")
        val toParts = pair.to.id.split(":")
        val tariffZone = fromParts.first()
        val fromId = fromParts.lastOrNull() ?: pair.from.id
        val toId = toParts.lastOrNull() ?: pair.to.id
        val version = "1"
        val timestamp =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now())
        val serviceLinkId = "$tariffZone:ServiceLink:$fromId-$toId"
        val linkSequenceProjectionId = "$tariffZone:LinkSequenceProjection:${timestamp}_${fromId}_${toId}"

        val serviceLinkElement = document.createElement("ServiceLink")
        serviceLinkElement.setAttribute("id", serviceLinkId)
        serviceLinkElement.setAttribute("version", version)

        // Only use OSRM distance, skip distance element if not available
        if (route?.distance != null) {
            val distanceElement = document.createElement("Distance")
            distanceElement.textContent = route.distance.toInt().toString()
            serviceLinkElement.appendChild(distanceElement)
        }

        // Add projections with GML LineString
        val projectionsElement = document.createElement("projections")
        val linkSequenceProjection = document.createElement("LinkSequenceProjection")
        linkSequenceProjection.setAttribute("id", linkSequenceProjectionId)
        linkSequenceProjection.setAttribute("version", "1")
        val gmlLineString = document.createElement( "gml:LineString")
        gmlLineString.setAttribute("srsName", SRS_NAME_WGS84)
        gmlLineString.setAttribute("gml:id", "lsp_${fromId}_${toId}")
        val coordinates = route?.geometry?.coordinates ?: listOf(
            listOf(pair.from.coordinate.longitude.toDouble(), pair.from.coordinate.latitude.toDouble()),
            listOf(pair.to.coordinate.longitude.toDouble(), pair.to.coordinate.latitude.toDouble())
        )
        val posListString = coordinates.joinToString(" ") { coord -> "${coord[1]} ${coord[0]}" }
        val posList = document.createElement( "gml:posList")
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

    private fun ensureGmlNamespace(document: Document) {
        val root = document.documentElement
        val gmlNamespace = "http://www.opengis.net/gml"
        val hasGmlNamespace = root.hasAttribute("xmlns:gml")
        if (!hasGmlNamespace) {
            root.setAttribute("xmlns:gml", gmlNamespace)
        }
    }

    suspend fun processNetexFileWithRoutes(inputStream: InputStream): Pair<ByteArray, Int> {
        logger.info("Starting NeTEx file processing with OSRM routes")
        val document = parseXmlDocument(inputStream)
        val stopPoints = extractStopPoints(document)
        val existingServiceLinks = extractExistingServiceLinks(document)
        val stationPairs = generateStationPairs(stopPoints, existingServiceLinks)
        logger.info("Generated ${stationPairs.size} station pairs that need service links")
        var addedCount = 0

        var serviceLinksSection = document.getElementsByTagName("serviceLinks").item(0) as? Element
        if (serviceLinksSection == null) {
            val commonFrame = document.getElementsByTagName("ServiceFrame").item(0) as? Element
                ?: document.getElementsByTagName("CompositeFrame").item(0) as? Element
                ?: document.documentElement
            serviceLinksSection = document.createElement("serviceLinks")
            commonFrame.appendChild(serviceLinksSection)
        }
        for (pair in stationPairs) {
            val route = try {
                osrmService.calculateRoute(pair.from.coordinate, pair.to.coordinate, pair.from.transportMode)
            } catch (e: Exception) {
                logger.warn("OSRM route failed, using direct line for ${pair.from.id} to ${pair.to.id}", e)
                null
            }
            val serviceLinkElement = createServiceLinkElementWithRoute(document, pair, route)
            serviceLinksSection.appendChild(serviceLinkElement)
            addedCount++
        }
        ensureGmlNamespace(document)
        val modifiedXml = documentToByteArray(document)
        logger.info("NeTEx processing completed. Added $addedCount service links")
        return Pair(modifiedXml, addedCount)
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
        const val SRS_NAME_WGS84: String = "ESPG:4326"
    }
}

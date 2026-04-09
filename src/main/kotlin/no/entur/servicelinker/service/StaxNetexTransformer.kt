package no.entur.servicelinker.service

import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.XMLStreamWriter

/**
 * Streaming XML transformer that injects ServiceLinks, stub ScheduledStopPoints,
 * and linksInSequence into NeTEx files without building a DOM tree.
 *
 * This replaces the DOM-based injection approach for Pass 2, reducing memory from
 * O(file-size * 3-10x) to O(2 * file-size + injection-data) — critical for large
 * NeTEx files (50MB+).
 *
 * Assumes a single target ServiceFrame per file. If a file contains multiple
 * ServiceFrames, only the first one receives injections.
 */
@Component
class StaxNetexTransformer {

    fun transform(input: ByteArray, plan: InjectionPlan): ByteArray {
        if (plan.isEmpty) return input

        val reader = createInputFactory().createXMLStreamReader(ByteArrayInputStream(input))
        val baos = ByteArrayOutputStream(input.size + estimateInjectionSize(plan))
        val writer = createOutputFactory().createXMLStreamWriter(baos, "UTF-8")

        try {
            // Emit the XML declaration before entering the event loop.
            // The reader starts at START_DOCUMENT but next() moves past it.
            writer.writeStartDocument(reader.characterEncodingScheme ?: "UTF-8", reader.version ?: "1.0")

            val elementStack = ArrayDeque<String>()
            var stopsInjected = plan.stubScheduledStopPoints.isEmpty()
            var linksInjected = plan.newServiceLinks.isEmpty()
            var currentJpId: String? = null
            var skipping = false
            var skipDepth = 0
            var insideExistingServiceLinks = false
            var insideExistingScheduledStopPoints = false

            while (reader.hasNext()) {
                val event = reader.next()

                // When skipping an existing linksInSequence subtree, consume events silently
                if (skipping) {
                    when (event) {
                        XMLStreamConstants.START_ELEMENT -> skipDepth++
                        XMLStreamConstants.END_ELEMENT -> {
                            skipDepth--
                            if (skipDepth == 0) skipping = false
                        }
                    }
                    continue
                }

                when (event) {
                    XMLStreamConstants.START_DOCUMENT -> {} // handled before the loop

                    XMLStreamConstants.START_ELEMENT -> {
                        val localName = reader.localName
                        val parentName = elementStack.lastOrNull()

                        // Injection points: direct children of ServiceFrame
                        if (parentName == "ServiceFrame") {
                            if (!stopsInjected && localName in AFTER_SCHEDULED_STOP_POINTS) {
                                writeScheduledStopPointsSection(writer, plan.stubScheduledStopPoints)
                                stopsInjected = true
                            }
                            if (!linksInjected && localName in AFTER_SERVICE_LINKS) {
                                writeServiceLinksSection(writer, plan.newServiceLinks)
                                linksInjected = true
                            }
                        }

                        // Track existing container sections
                        if (parentName == "ServiceFrame") {
                            if (localName == "scheduledStopPoints") insideExistingScheduledStopPoints = true
                            if (localName == "serviceLinks") insideExistingServiceLinks = true
                        }

                        // Track JourneyPattern id for linksInSequence injection
                        if (localName == "JourneyPattern") {
                            currentJpId = reader.getAttributeValue(null, "id")
                        }

                        // Skip existing linksInSequence — will be replaced at JP close
                        if (localName == "linksInSequence" && currentJpId != null
                            && plan.journeyPatternLinks.containsKey(currentJpId)
                        ) {
                            skipping = true
                            skipDepth = 1
                            continue
                        }

                        copyStartElement(reader, writer, plan.needsGmlNamespace && elementStack.isEmpty())
                        elementStack.addLast(localName)
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        val localName = elementStack.removeLast()

                        // Append stubs to existing <scheduledStopPoints> before its close tag
                        if (localName == "scheduledStopPoints" && insideExistingScheduledStopPoints) {
                            if (!stopsInjected) {
                                writeStubStopPoints(writer, plan.stubScheduledStopPoints)
                                stopsInjected = true
                            }
                            insideExistingScheduledStopPoints = false
                        }

                        // Append new ServiceLinks to existing <serviceLinks> before its close tag
                        if (localName == "serviceLinks" && insideExistingServiceLinks) {
                            if (!linksInjected) {
                                writeServiceLinkElements(writer, plan.newServiceLinks)
                                linksInjected = true
                            }
                            insideExistingServiceLinks = false
                        }

                        // Inject merged linksInSequence before </JourneyPattern>
                        if (localName == "JourneyPattern" && currentJpId != null) {
                            val links = plan.journeyPatternLinks[currentJpId]
                            if (!links.isNullOrEmpty()) {
                                writeLinksInSequence(writer, links)
                            }
                            currentJpId = null
                        }

                        // Fallback: inject remaining sections before </ServiceFrame>
                        if (localName == "ServiceFrame") {
                            if (!stopsInjected) {
                                writeScheduledStopPointsSection(writer, plan.stubScheduledStopPoints)
                                stopsInjected = true
                            }
                            if (!linksInjected) {
                                writeServiceLinksSection(writer, plan.newServiceLinks)
                                linksInjected = true
                            }
                        }

                        writer.writeEndElement()
                    }

                    XMLStreamConstants.CHARACTERS -> writer.writeCharacters(reader.text)
                    XMLStreamConstants.CDATA -> writer.writeCData(reader.text)
                    XMLStreamConstants.COMMENT -> writer.writeComment(reader.text)
                    XMLStreamConstants.SPACE -> writer.writeCharacters(reader.text)
                    XMLStreamConstants.PROCESSING_INSTRUCTION ->
                        writer.writeProcessingInstruction(reader.piTarget, reader.piData ?: "")
                    XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument()
                }
            }

            writer.flush()
        } finally {
            writer.close()
            reader.close()
        }

        return baos.toByteArray()
    }

    private fun copyStartElement(reader: XMLStreamReader, writer: XMLStreamWriter, addGmlNamespace: Boolean) {
        val namespaceURI = reader.namespaceURI ?: ""
        val localName = reader.localName
        val prefix = if (namespaceURI == GML_NS) GML_PREFIX else (reader.prefix ?: "")

        writer.writeStartElement(prefix, localName, namespaceURI)

        // Copy namespace declarations, rewriting any GML binding to use "gis" prefix
        var gmlDeclared = false
        for (i in 0 until reader.namespaceCount) {
            val nsUri = reader.getNamespaceURI(i) ?: ""
            if (nsUri == GML_NS) {
                if (!gmlDeclared) {
                    writer.writeNamespace(GML_PREFIX, GML_NS)
                    gmlDeclared = true
                }
            } else {
                writer.writeNamespace(reader.getNamespacePrefix(i) ?: "", nsUri)
            }
        }

        // Ensure GML namespace on root element when new ServiceLinks use GML geometry
        if (addGmlNamespace && !gmlDeclared) {
            writer.writeNamespace(GML_PREFIX, GML_NS)
        }

        // Copy attributes, rewriting GML-namespace attributes to "gis" prefix
        for (i in 0 until reader.attributeCount) {
            val attrNs = reader.getAttributeNamespace(i) ?: ""
            val attrLocalName = reader.getAttributeLocalName(i)
            val attrValue = reader.getAttributeValue(i)

            if (attrNs.isNotEmpty()) {
                val attrPrefix = if (attrNs == GML_NS) GML_PREFIX else (reader.getAttributePrefix(i) ?: "")
                writer.writeAttribute(attrPrefix, attrNs, attrLocalName, attrValue)
            } else {
                writer.writeAttribute(attrLocalName, attrValue)
            }
        }
    }

    // --- XML generation helpers ---

    private fun writeScheduledStopPointsSection(writer: XMLStreamWriter, stubs: Map<String, String>) {
        writer.writeStartElement("", "scheduledStopPoints", NETEX_NS)
        writeStubStopPoints(writer, stubs)
        writer.writeEndElement()
    }

    private fun writeStubStopPoints(writer: XMLStreamWriter, stubs: Map<String, String>) {
        for ((id, version) in stubs) {
            writer.writeEmptyElement("", "ScheduledStopPoint", NETEX_NS)
            writer.writeAttribute("id", id)
            writer.writeAttribute("version", version)
        }
    }

    private fun writeServiceLinksSection(writer: XMLStreamWriter, serviceLinks: List<ServiceLinkData>) {
        writer.writeStartElement("", "serviceLinks", NETEX_NS)
        writeServiceLinkElements(writer, serviceLinks)
        writer.writeEndElement()
    }

    private fun writeServiceLinkElements(writer: XMLStreamWriter, serviceLinks: List<ServiceLinkData>) {
        for (sl in serviceLinks) {
            writer.writeStartElement("", "ServiceLink", NETEX_NS)
            writer.writeAttribute("id", sl.id)
            writer.writeAttribute("version", sl.version)

            if (sl.distance != null) {
                writer.writeStartElement("", "Distance", NETEX_NS)
                writer.writeCharacters(sl.distance.toString())
                writer.writeEndElement()
            }

            writer.writeStartElement("", "projections", NETEX_NS)
            writer.writeStartElement("", "LinkSequenceProjection", NETEX_NS)
            writer.writeAttribute("id", sl.projectionId)
            writer.writeAttribute("version", "1")

            writer.writeStartElement(GML_PREFIX, "LineString", GML_NS)
            writer.writeAttribute("srsName", SRS_NAME_WGS84)
            writer.writeAttribute(GML_PREFIX, GML_NS, "id", sl.gmlLineStringId)

            writer.writeStartElement(GML_PREFIX, "posList", GML_NS)
            writer.writeCharacters(sl.posList)
            writer.writeEndElement() // posList

            writer.writeEndElement() // LineString
            writer.writeEndElement() // LinkSequenceProjection
            writer.writeEndElement() // projections

            writer.writeEmptyElement("", "FromPointRef", NETEX_NS)
            writer.writeAttribute("ref", sl.fromPointRef)
            writer.writeAttribute("version", sl.fromPointVersion)

            writer.writeEmptyElement("", "ToPointRef", NETEX_NS)
            writer.writeAttribute("ref", sl.toPointRef)
            writer.writeAttribute("version", sl.toPointVersion)

            writer.writeEndElement() // ServiceLink
        }
    }

    private fun writeLinksInSequence(writer: XMLStreamWriter, entries: List<SlijpEntry>) {
        writer.writeStartElement("", "linksInSequence", NETEX_NS)
        for (entry in entries) {
            writer.writeStartElement("", "ServiceLinkInJourneyPattern", NETEX_NS)
            writer.writeAttribute("id", entry.id)
            writer.writeAttribute("version", entry.version)
            writer.writeAttribute("order", entry.order.toString())

            writer.writeEmptyElement("", "ServiceLinkRef", NETEX_NS)
            writer.writeAttribute("ref", entry.serviceLinkRef)
            if (entry.serviceLinkRefVersion != null) {
                writer.writeAttribute("version", entry.serviceLinkRefVersion)
            }

            writer.writeEndElement() // ServiceLinkInJourneyPattern
        }
        writer.writeEndElement() // linksInSequence
    }

    private fun estimateInjectionSize(plan: InjectionPlan): Int =
        plan.newServiceLinks.size * 500 +
                plan.journeyPatternLinks.values.sumOf { it.size * 200 } +
                plan.stubScheduledStopPoints.size * 100

    companion object {
        const val NETEX_NS = "http://www.netex.org.uk/netex"
        const val GML_NS = "http://www.opengis.net/gml/3.2"
        const val GML_PREFIX = "gis"
        const val SRS_NAME_WGS84 = "EPSG:4326"

        // Created per-call rather than shared static — XMLInputFactory/XMLOutputFactory
        // are not guaranteed thread-safe by the StAX spec.
        private fun createInputFactory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }

        private fun createOutputFactory(): XMLOutputFactory = XMLOutputFactory.newInstance().apply {
            setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, true)
        }

        // NeTEx ServiceFrame elements that must appear after <scheduledStopPoints> in schema order
        private val AFTER_SCHEDULED_STOP_POINTS = setOf(
            "serviceLinks", "servicePatterns",
            "journeyPatterns", "stopAssignments", "connections",
            "timingPoints", "timingLinks", "tariffZones",
        )

        // NeTEx ServiceFrame elements that must appear after <serviceLinks> in schema order
        private val AFTER_SERVICE_LINKS = setOf(
            "journeyPatterns", "stopAssignments", "connections",
            "timingPoints", "timingLinks", "tariffZones",
            "routingConstraintZones", "transferRestrictions", "serviceExclusions",
            "timeDemandTypes", "timeDemandTypeAssignments", "timingLinkGroups",
            "notices", "noticeAssignments", "logicalDisplays",
            "displayAssignments", "passengerInformationEquipments"
        )
    }
}

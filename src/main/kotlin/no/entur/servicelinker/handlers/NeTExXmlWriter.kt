package no.entur.servicelinker.handlers

import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import org.entur.netex.tools.lib.output.DelegatingXMLElementWriter
import org.xml.sax.helpers.AttributesImpl

/**
 * Writes NeTEx XML fragments (ServiceLinks, stub stops, linksInSequence)
 * via a [DelegatingXMLElementWriter].
 */
object NeTExXmlWriter {
    /** No namespace URI — matches non-namespace-aware SAX parser behavior.
     *  NeTEx elements inherit the default namespace from the document root. */
    private const val NO_NS = ""
    private const val SRS_NAME_WGS84 = "EPSG:4326"

    fun writeScheduledStopPointsSection(
        stubs: Map<String, String>,
        writer: DelegatingXMLElementWriter,
    ) {
        writer.startElement(NO_NS, "scheduledStopPoints", "scheduledStopPoints", AttributesImpl())
        writeStubStopPoints(stubs, writer)
        writer.endElement(NO_NS, "scheduledStopPoints", "scheduledStopPoints")
    }

    fun writeStubStopPoints(
        stubs: Map<String, String>,
        writer: DelegatingXMLElementWriter,
    ) {
        for ((id, version) in stubs) {
            val a = attr("id" to id, "version" to version)
            writer.startElement(NO_NS, "ScheduledStopPoint", "ScheduledStopPoint", a)
            writer.endElement(NO_NS, "ScheduledStopPoint", "ScheduledStopPoint")
        }
    }

    fun writeServiceLinksSection(
        serviceLinks: List<ServiceLinkData>,
        writer: DelegatingXMLElementWriter,
    ) {
        writer.startElement(NO_NS, "serviceLinks", "serviceLinks", AttributesImpl())
        writeServiceLinkElements(serviceLinks, writer)
        writer.endElement(NO_NS, "serviceLinks", "serviceLinks")
    }

    fun writeServiceLinkElements(
        serviceLinks: List<ServiceLinkData>,
        writer: DelegatingXMLElementWriter,
    ) {
        for (sl in serviceLinks) {
            writer.startElement(NO_NS, "ServiceLink", "ServiceLink", attr("id" to sl.id, "version" to sl.version))

            if (sl.distance != null) {
                writer.startElement(NO_NS, "Distance", "Distance", AttributesImpl())
                val text = sl.distance.toString()
                writer.characters(text.toCharArray(), 0, text.length)
                writer.endElement(NO_NS, "Distance", "Distance")
            }

            writer.startElement(NO_NS, "projections", "projections", AttributesImpl())
            writer.startElement(
                NO_NS,
                "LinkSequenceProjection",
                "LinkSequenceProjection",
                attr("id" to sl.projectionId, "version" to "1"),
            )

            val lineStringAttrs =
                AttributesImpl().apply {
                    addAttribute(NO_NS, "srsName", "srsName", "CDATA", SRS_NAME_WGS84)
                    addAttribute(GML_NS, "id", "$GML_PREFIX:id", "CDATA", sl.gmlLineStringId)
                }
            writer.startElement(GML_NS, "LineString", "$GML_PREFIX:LineString", lineStringAttrs)

            writer.startElement(GML_NS, "posList", "$GML_PREFIX:posList", AttributesImpl())
            writer.characters(sl.posList.toCharArray(), 0, sl.posList.length)
            writer.endElement(GML_NS, "posList", "$GML_PREFIX:posList")

            writer.endElement(GML_NS, "LineString", "$GML_PREFIX:LineString")
            writer.endElement(NO_NS, "LinkSequenceProjection", "LinkSequenceProjection")
            writer.endElement(NO_NS, "projections", "projections")

            writer.startElement(
                NO_NS,
                "FromPointRef",
                "FromPointRef",
                attr("ref" to sl.fromPointRef, "version" to sl.fromPointVersion),
            )
            writer.endElement(NO_NS, "FromPointRef", "FromPointRef")

            writer.startElement(
                NO_NS,
                "ToPointRef",
                "ToPointRef",
                attr("ref" to sl.toPointRef, "version" to sl.toPointVersion),
            )
            writer.endElement(NO_NS, "ToPointRef", "ToPointRef")

            writer.endElement(NO_NS, "ServiceLink", "ServiceLink")
        }
    }

    fun writeLinksInSequence(
        entries: List<SlijpEntry>,
        writer: DelegatingXMLElementWriter,
    ) {
        writer.startElement(NO_NS, "linksInSequence", "linksInSequence", AttributesImpl())
        for (entry in entries) {
            writer.startElement(
                NO_NS,
                "ServiceLinkInJourneyPattern",
                "ServiceLinkInJourneyPattern",
                attr("id" to entry.id, "version" to entry.version, "order" to entry.order.toString()),
            )

            val refAttrs =
                if (entry.serviceLinkRefVersion != null) {
                    attr("ref" to entry.serviceLinkRef, "version" to entry.serviceLinkRefVersion)
                } else {
                    attr("ref" to entry.serviceLinkRef)
                }
            writer.startElement(NO_NS, "ServiceLinkRef", "ServiceLinkRef", refAttrs)
            writer.endElement(NO_NS, "ServiceLinkRef", "ServiceLinkRef")

            writer.endElement(NO_NS, "ServiceLinkInJourneyPattern", "ServiceLinkInJourneyPattern")
        }
        writer.endElement(NO_NS, "linksInSequence", "linksInSequence")
    }

    private fun attr(vararg pairs: Pair<String, String>): AttributesImpl {
        val attrs = AttributesImpl()
        for ((name, value) in pairs) {
            attrs.addAttribute(NO_NS, name, name, "CDATA", value)
        }
        return attrs
    }
}

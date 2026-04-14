package no.entur.servicelinker.handlers

import no.entur.servicelinker.model.InjectionPlan
import no.entur.servicelinker.model.ServiceLinkData
import no.entur.servicelinker.model.SlijpEntry
import org.entur.netex.tools.lib.output.DelegatingXMLElementWriter
import org.entur.netex.tools.lib.output.XMLElementHandler
import org.xml.sax.Attributes
import org.xml.sax.helpers.AttributesImpl

private const val NETEX_NS = "http://www.netex.org.uk/netex"
private const val GML_NS = "http://www.opengis.net/gml/3.2"
private const val GML_PREFIX = "gis"
private const val SRS_NAME_WGS84 = "EPSG:4326"

/**
 * Shared mutable state between handlers processing a single document.
 *
 * Flags start as `true` when the plan has nothing to inject for that
 * section, so handlers never fire unnecessarily — "nothing to inject"
 * and "already injected" are the same state.
 */
class InjectionContext(val plan: InjectionPlan) {
    var stopsInjected = plan.stubScheduledStopPoints.isEmpty()
    var linksInjected = plan.newServiceLinks.isEmpty()
    var currentJpId: String? = null
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

/** Pass-through base: forwards all events unchanged. Override the method that injects. */
private open class PassThroughHandler : XMLElementHandler {
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?, writer: DelegatingXMLElementWriter) {
        writer.startElement(uri, localName, qName, attributes)
    }

    override fun characters(ch: CharArray?, start: Int, length: Int, writer: DelegatingXMLElementWriter) {
        writer.characters(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        writer.endElement(uri, localName, qName)
    }
}

/**
 * Registered on `…/ServiceFrame/serviceLinks`. Appends new ServiceLink
 * elements before forwarding the close tag.
 */
private class ServiceLinksHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.linksInjected) {
            writeServiceLinkElements(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

/**
 * Registered on `…/ServiceFrame/scheduledStopPoints`. Appends stub
 * ScheduledStopPoint elements before forwarding the close tag.
 */
private class ScheduledStopPointsHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            writeStubStopPoints(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

/**
 * Registered on `…/journeyPatterns/JourneyPattern`. Reads the JP id on
 * open and emits merged linksInSequence before forwarding the close tag.
 */
private class JourneyPatternHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?, writer: DelegatingXMLElementWriter) {
        ctx.currentJpId = attributes?.getValue("id")
        writer.startElement(uri, localName, qName, attributes)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        val jpId = ctx.currentJpId
        if (jpId != null) {
            val links = ctx.plan.journeyPatternLinks[jpId]
            if (!links.isNullOrEmpty()) {
                writeLinksInSequence(links, writer)
            }
        }
        ctx.currentJpId = null
        writer.endElement(uri, localName, qName)
    }
}

/**
 * Registered on ServiceFrame children that appear AFTER the injection
 * points in NeTEx schema order (e.g. `journeyPatterns`, `stopAssignments`).
 * Emits any missing sections BEFORE forwarding its own start tag, ensuring
 * correct schema ordering.
 */
private class InsertBeforeHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            writeScheduledStopPointsSection(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        if (!ctx.linksInjected) {
            writeServiceLinksSection(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.startElement(uri, localName, qName, attributes)
    }
}

/**
 * Registered on `…/ServiceFrame`. Last-resort fallback for files where no
 * schema-ordered trigger child exists (e.g. an empty ServiceFrame).
 */
private class ServiceFrameHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            writeScheduledStopPointsSection(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        if (!ctx.linksInjected) {
            writeServiceLinksSection(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

// ---------------------------------------------------------------------------
// Handler map factory
// ---------------------------------------------------------------------------

/** Known ServiceFrame ancestor patterns in NeTEx files. */
private val SERVICE_FRAME_PREFIXES = listOf(
    "/PublicationDelivery/dataObjects/CompositeFrame/frames/ServiceFrame",
    "/PublicationDelivery/dataObjects/ServiceFrame",
)

// NeTEx ServiceFrame children that appear after both scheduledStopPoints and
// serviceLinks in schema order. An InsertBeforeHandler on each ensures new
// sections are emitted at the correct position.
private val ELEMENTS_AFTER_INJECTION_POINTS = listOf(
    "journeyPatterns", "stopAssignments", "connections",
    "timingPoints", "timingLinks", "tariffZones",
    "routingConstraintZones", "transferRestrictions", "serviceExclusions",
    "timeDemandTypes", "timeDemandTypeAssignments", "timingLinkGroups",
    "notices", "noticeAssignments", "logicalDisplays",
    "displayAssignments", "passengerInformationEquipments",
)

/**
 * Build the `customElementHandlers` map for a single document's injection.
 * Registers handlers at both common ServiceFrame path variants — only the
 * one matching the actual document structure will fire.
 */
fun buildHandlerMap(plan: InjectionPlan): Map<String, XMLElementHandler> {
    val ctx = InjectionContext(plan)
    val handlers = mutableMapOf<String, XMLElementHandler>()

    for (prefix in SERVICE_FRAME_PREFIXES) {
        handlers["$prefix/serviceLinks"] = ServiceLinksHandler(ctx)
        handlers["$prefix/scheduledStopPoints"] = ScheduledStopPointsHandler(ctx)
        handlers["$prefix/journeyPatterns/JourneyPattern"] = JourneyPatternHandler(ctx)
        // Schema-ordered trigger: emit missing sections before these children
        for (child in ELEMENTS_AFTER_INJECTION_POINTS) {
            handlers["$prefix/$child"] = InsertBeforeHandler(ctx)
        }
        // Last-resort fallback for empty ServiceFrame
        handlers[prefix] = ServiceFrameHandler(ctx)
    }

    return handlers
}

// ---------------------------------------------------------------------------
// XML writing helpers
// ---------------------------------------------------------------------------

private fun attr(vararg pairs: Pair<String, String>): AttributesImpl {
    val attrs = AttributesImpl()
    for ((name, value) in pairs) {
        attrs.addAttribute("", name, name, "CDATA", value)
    }
    return attrs
}

private fun writeScheduledStopPointsSection(stubs: Map<String, String>, writer: DelegatingXMLElementWriter) {
    writer.startElement(NETEX_NS, "scheduledStopPoints", "scheduledStopPoints", AttributesImpl())
    writeStubStopPoints(stubs, writer)
    writer.endElement(NETEX_NS, "scheduledStopPoints", "scheduledStopPoints")
}

private fun writeStubStopPoints(stubs: Map<String, String>, writer: DelegatingXMLElementWriter) {
    for ((id, version) in stubs) {
        val a = attr("id" to id, "version" to version)
        writer.startElement(NETEX_NS, "ScheduledStopPoint", "ScheduledStopPoint", a)
        writer.endElement(NETEX_NS, "ScheduledStopPoint", "ScheduledStopPoint")
    }
}

private fun writeServiceLinksSection(serviceLinks: List<ServiceLinkData>, writer: DelegatingXMLElementWriter) {
    writer.startElement(NETEX_NS, "serviceLinks", "serviceLinks", AttributesImpl())
    writeServiceLinkElements(serviceLinks, writer)
    writer.endElement(NETEX_NS, "serviceLinks", "serviceLinks")
}

private fun writeServiceLinkElements(serviceLinks: List<ServiceLinkData>, writer: DelegatingXMLElementWriter) {
    for (sl in serviceLinks) {
        writer.startElement(NETEX_NS, "ServiceLink", "ServiceLink", attr("id" to sl.id, "version" to sl.version))

        if (sl.distance != null) {
            writer.startElement(NETEX_NS, "Distance", "Distance", AttributesImpl())
            val text = sl.distance.toString()
            writer.characters(text.toCharArray(), 0, text.length)
            writer.endElement(NETEX_NS, "Distance", "Distance")
        }

        writer.startElement(NETEX_NS, "projections", "projections", AttributesImpl())
        writer.startElement(NETEX_NS, "LinkSequenceProjection", "LinkSequenceProjection",
            attr("id" to sl.projectionId, "version" to "1"))

        val lineStringAttrs = AttributesImpl().apply {
            addAttribute("", "srsName", "srsName", "CDATA", SRS_NAME_WGS84)
            addAttribute(GML_NS, "id", "$GML_PREFIX:id", "CDATA", sl.gmlLineStringId)
        }
        writer.startElement(GML_NS, "LineString", "$GML_PREFIX:LineString", lineStringAttrs)

        writer.startElement(GML_NS, "posList", "$GML_PREFIX:posList", AttributesImpl())
        writer.characters(sl.posList.toCharArray(), 0, sl.posList.length)
        writer.endElement(GML_NS, "posList", "$GML_PREFIX:posList")

        writer.endElement(GML_NS, "LineString", "$GML_PREFIX:LineString")
        writer.endElement(NETEX_NS, "LinkSequenceProjection", "LinkSequenceProjection")
        writer.endElement(NETEX_NS, "projections", "projections")

        writer.startElement(NETEX_NS, "FromPointRef", "FromPointRef",
            attr("ref" to sl.fromPointRef, "version" to sl.fromPointVersion))
        writer.endElement(NETEX_NS, "FromPointRef", "FromPointRef")

        writer.startElement(NETEX_NS, "ToPointRef", "ToPointRef",
            attr("ref" to sl.toPointRef, "version" to sl.toPointVersion))
        writer.endElement(NETEX_NS, "ToPointRef", "ToPointRef")

        writer.endElement(NETEX_NS, "ServiceLink", "ServiceLink")
    }
}

private fun writeLinksInSequence(entries: List<SlijpEntry>, writer: DelegatingXMLElementWriter) {
    writer.startElement(NETEX_NS, "linksInSequence", "linksInSequence", AttributesImpl())
    for (entry in entries) {
        writer.startElement(NETEX_NS, "ServiceLinkInJourneyPattern", "ServiceLinkInJourneyPattern",
            attr("id" to entry.id, "version" to entry.version, "order" to entry.order.toString()))

        val refAttrs = if (entry.serviceLinkRefVersion != null) {
            attr("ref" to entry.serviceLinkRef, "version" to entry.serviceLinkRefVersion)
        } else {
            attr("ref" to entry.serviceLinkRef)
        }
        writer.startElement(NETEX_NS, "ServiceLinkRef", "ServiceLinkRef", refAttrs)
        writer.endElement(NETEX_NS, "ServiceLinkRef", "ServiceLinkRef")

        writer.endElement(NETEX_NS, "ServiceLinkInJourneyPattern", "ServiceLinkInJourneyPattern")
    }
    writer.endElement(NETEX_NS, "linksInSequence", "linksInSequence")
}

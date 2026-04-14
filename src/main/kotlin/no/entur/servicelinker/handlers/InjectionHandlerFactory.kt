package no.entur.servicelinker.handlers

import no.entur.servicelinker.model.InjectionPlan
import org.entur.netex.tools.lib.output.DelegatingXMLElementWriter
import org.entur.netex.tools.lib.output.XMLElementHandler
import org.xml.sax.Attributes
import org.xml.sax.helpers.AttributesImpl

/**
 * Builds the `customElementHandlers` map for injecting ServiceLinks, stub
 * stops, and linksInSequence into a single NeTEx document.
 *
 * Handlers are registered at both common ServiceFrame path variants
 * (CompositeFrame and direct) — only the one matching the actual document
 * structure will fire.
 */
object InjectionHandlerFactory {

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

    fun buildHandlerMap(plan: InjectionPlan): Map<String, XMLElementHandler> {
        val ctx = InjectionContext(plan)
        val handlers = mutableMapOf<String, XMLElementHandler>()

        if (plan.newServiceLinks.isNotEmpty()) {
            handlers["/PublicationDelivery"] = GmlNamespaceHandler()
        }

        for (prefix in SERVICE_FRAME_PREFIXES) {
            handlers["$prefix/serviceLinks"] = ServiceLinksHandler(ctx)
            handlers["$prefix/scheduledStopPoints"] = ScheduledStopPointsHandler(ctx)
            handlers["$prefix/journeyPatterns/JourneyPattern"] = JourneyPatternHandler(ctx)
            for (child in ELEMENTS_AFTER_INJECTION_POINTS) {
                handlers["$prefix/$child"] = InsertBeforeHandler(ctx)
            }
            handlers[prefix] = ServiceFrameHandler(ctx)
        }

        return handlers
    }
}

// ---------------------------------------------------------------------------
// Handler implementations — package-private, used only by the factory
// ---------------------------------------------------------------------------

/**
 * Declares the gis: prefix on the root element via startPrefixMapping so
 * injected GML elements don't each carry their own xmlns:gis declaration.
 * Strips any pass-through xmlns:gml/xmlns:gis attribute to avoid a duplicate
 * declaration (the non-namespace-aware parser passes these as regular attrs).
 */
private class GmlNamespaceHandler : PassThroughHandler() {
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?, writer: DelegatingXMLElementWriter) {
        writer.startPrefixMapping(GML_PREFIX, GML_NS)
        writer.startElement(uri, localName, qName, stripGmlNamespaceAttr(attributes))
    }

    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        writer.endElement(uri, localName, qName)
        writer.endPrefixMapping(GML_PREFIX)
    }

    private fun stripGmlNamespaceAttr(attributes: Attributes?): Attributes? {
        if (attributes == null) return null
        val filtered = AttributesImpl()
        var removedAny = false
        for (i in 0 until attributes.length) {
            val qName = attributes.getQName(i)
            if (qName == "xmlns:gml" || qName == "xmlns:gis") {
                removedAny = true
                continue
            }
            filtered.addAttribute(
                attributes.getURI(i), attributes.getLocalName(i),
                attributes.getQName(i), attributes.getType(i), attributes.getValue(i),
            )
        }
        return if (removedAny) filtered else attributes
    }
}

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

/** Appends new ServiceLink elements before forwarding the serviceLinks close tag. */
private class ServiceLinksHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.linksInjected) {
            NeTExXmlWriter.writeServiceLinkElements(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

/** Appends stub ScheduledStopPoint elements before forwarding the scheduledStopPoints close tag. */
private class ScheduledStopPointsHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            NeTExXmlWriter.writeStubStopPoints(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

/** Reads JP id on open, emits merged linksInSequence before forwarding the close tag. */
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
                NeTExXmlWriter.writeLinksInSequence(links, writer)
            }
        }
        ctx.currentJpId = null
        writer.endElement(uri, localName, qName)
    }
}

/** Emits missing sections BEFORE forwarding start tag — ensures correct schema ordering. */
private class InsertBeforeHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            NeTExXmlWriter.writeScheduledStopPointsSection(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        if (!ctx.linksInjected) {
            NeTExXmlWriter.writeServiceLinksSection(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.startElement(uri, localName, qName, attributes)
    }
}

/** Last-resort fallback: emits remaining sections before the ServiceFrame close tag. */
private class ServiceFrameHandler(private val ctx: InjectionContext) : PassThroughHandler() {
    override fun endElement(uri: String?, localName: String?, qName: String?, writer: DelegatingXMLElementWriter) {
        if (!ctx.stopsInjected) {
            NeTExXmlWriter.writeScheduledStopPointsSection(ctx.plan.stubScheduledStopPoints, writer)
            ctx.stopsInjected = true
        }
        if (!ctx.linksInjected) {
            NeTExXmlWriter.writeServiceLinksSection(ctx.plan.newServiceLinks, writer)
            ctx.linksInjected = true
        }
        writer.endElement(uri, localName, qName)
    }
}

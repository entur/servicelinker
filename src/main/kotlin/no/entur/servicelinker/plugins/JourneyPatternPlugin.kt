package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.TransportMode
import org.entur.netex.tools.lib.model.Entity
import org.entur.netex.tools.lib.plugin.AbstractNetexPlugin
import org.xml.sax.Attributes

/**
 * Mirrors `NetexProcessor.collectJpStopSequences`, `collectExistingSlijpEntries`,
 * and captures the per-JP mode inputs (direct TransportMode/VehicleMode, LineRef, RouteRef)
 * that `buildJpModeMap` resolves using the Line/Route maps supplied by [LineRoutePlugin].
 */
internal class JourneyPatternPlugin : AbstractNetexPlugin() {
    data class JpModeInputs(
        val directMode: TransportMode?,
        val lineRef: String?,
        val routeRef: String?,
    )

    private val _stopSequences = mutableMapOf<String, Map<String, List<String>>>()
    private val _existingSlijp = mutableMapOf<String, Map<String, Map<String, ExistingSlijpInfo>>>()
    private val _jpModeInputs = mutableMapOf<String, Map<String, JpModeInputs>>()

    val stopSequences: Map<String, Map<String, List<String>>> get() = _stopSequences
    val existingSlijp: Map<String, Map<String, Map<String, ExistingSlijpInfo>>> get() = _existingSlijp
    val jpModeInputs: Map<String, Map<String, JpModeInputs>> get() = _jpModeInputs

    // Order-preserving: downstream (rawPairRefs, injection plans) walks JPs in document order.
    private var docSequences: MutableMap<String, List<String>> = linkedMapOf()
    private var docSlijp: MutableMap<String, Map<String, ExistingSlijpInfo>> = linkedMapOf()
    private var docModes: MutableMap<String, JpModeInputs> = linkedMapOf()

    private var jpId: String? = null
    private var jpDirectMode: TransportMode? = null
    private var jpLineRef: String? = null
    private var jpRouteRef: String? = null
    private val orderedRefs = mutableListOf<Pair<Int, String>>()
    private val slijpEntries = mutableMapOf<String, ExistingSlijpInfo>()

    private var inSpijp = false
    private var spijpOrder: Int = 0
    private var spijpOrderFallback = 0
    private var spijpRef: String? = null

    private var inSlijp = false
    private var slijpId: String? = null
    private var slijpVersion: String? = null
    private var slijpServiceLinkRef: String? = null
    private var slijpServiceLinkRefVersion: String? = null

    private var modeText: StringBuilder? = null

    override fun getName() = "JourneyPatternPlugin"

    override fun getDescription() = "Collects JP stop sequences, existing SLIJPs, and mode inputs"

    override fun getSupportedElementTypes(): Set<String> =
        setOf(
            "JourneyPattern",
            "JourneyPattern/StopPointInJourneyPattern",
            "JourneyPattern/ScheduledStopPointRef",
            "JourneyPattern/ServiceLinkInJourneyPattern",
            "JourneyPattern/ServiceLinkRef",
            "JourneyPattern/LineRef",
            "JourneyPattern/RouteRef",
            "JourneyPattern/TransportMode",
            "JourneyPattern/VehicleMode",
            "JourneyPattern/VehicleModes",
        )

    override fun startElement(
        elementName: String,
        attributes: Attributes?,
        currentEntity: Entity?,
    ) {
        when (elementName) {
            "JourneyPattern" -> {
                resetJp(attributes)
            }

            "StopPointInJourneyPattern" -> {
                inSpijp = true
                spijpOrderFallback += 1
                spijpOrder = attributes?.getValue("order")?.toIntOrNull() ?: spijpOrderFallback
                spijpRef = null
            }

            "ScheduledStopPointRef" -> {
                if (inSpijp && spijpRef == null) {
                    spijpRef = attributes?.getValue("ref")
                }
            }

            "ServiceLinkInJourneyPattern" -> {
                inSlijp = true
                slijpId = attributes?.getValue("id")
                slijpVersion = attributes?.getValue("version")
                slijpServiceLinkRef = null
                slijpServiceLinkRefVersion = null
            }

            "ServiceLinkRef" -> {
                if (inSlijp && slijpServiceLinkRef == null) {
                    slijpServiceLinkRef = attributes?.getValue("ref")
                    slijpServiceLinkRefVersion = attributes?.getValue("version")?.takeIf { it.isNotBlank() }
                }
            }

            "LineRef" -> {
                if (jpLineRef == null) jpLineRef = attributes?.getValue("ref")
            }

            "RouteRef" -> {
                if (jpRouteRef == null) jpRouteRef = attributes?.getValue("ref")
            }

            "TransportMode", "VehicleMode", "VehicleModes" -> {
                if (jpDirectMode == null) modeText = StringBuilder()
            }
        }
    }

    override fun characters(
        elementName: String,
        ch: CharArray?,
        start: Int,
        length: Int,
    ) {
        if (ch != null) modeText?.append(ch, start, length)
    }

    override fun endElement(
        elementName: String,
        currentEntity: Entity?,
    ) {
        when (elementName) {
            "TransportMode", "VehicleMode", "VehicleModes" -> {
                if (jpDirectMode == null) jpDirectMode = parseTransportMode(modeText?.toString()?.trim())
                modeText = null
            }

            "StopPointInJourneyPattern" -> {
                spijpRef?.let { orderedRefs.add(spijpOrder to it) }
                inSpijp = false
                spijpRef = null
            }

            "ServiceLinkInJourneyPattern" -> {
                val ref = slijpServiceLinkRef
                if (ref != null) {
                    slijpEntries[ref] =
                        ExistingSlijpInfo(
                            id = slijpId ?: "",
                            version = slijpVersion?.ifBlank { null } ?: "1",
                            serviceLinkRefVersion = slijpServiceLinkRefVersion,
                        )
                }
                inSlijp = false
                slijpId = null
                slijpVersion = null
                slijpServiceLinkRef = null
                slijpServiceLinkRefVersion = null
            }

            "JourneyPattern" -> {
                val id = jpId
                if (id != null) {
                    val refs = orderedRefs.sortedBy { it.first }.map { it.second }
                    if (refs.size >= 2) docSequences[id] = refs
                    if (slijpEntries.isNotEmpty()) docSlijp[id] = slijpEntries.toMap()
                    docModes[id] = JpModeInputs(jpDirectMode, jpLineRef, jpRouteRef)
                }
                resetJp(null)
            }
        }
    }

    override fun endDocument(documentName: String) {
        _stopSequences[documentName] = docSequences.toMap()
        _existingSlijp[documentName] = docSlijp.toMap()
        _jpModeInputs[documentName] = docModes.toMap()
        docSequences = linkedMapOf()
        docSlijp = linkedMapOf()
        docModes = linkedMapOf()
        resetJp(null)
    }

    private fun resetJp(attributes: Attributes?) {
        jpId = attributes?.getValue("id")
        jpDirectMode = null
        jpLineRef = null
        jpRouteRef = null
        orderedRefs.clear()
        slijpEntries.clear()
        spijpOrderFallback = 0
        spijpOrder = 0
        spijpRef = null
        inSpijp = false
        inSlijp = false
        slijpId = null
        slijpVersion = null
        slijpServiceLinkRef = null
        slijpServiceLinkRefVersion = null
        modeText = null
    }
}

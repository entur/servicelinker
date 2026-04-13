package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.TransportMode
import org.entur.netex.tools.lib.model.Entity
import org.entur.netex.tools.lib.plugin.AbstractNetexPlugin
import org.xml.sax.Attributes

/**
 * Collects the per-document Line → TransportMode and Route → LineRef maps used by
 * `NetexProcessor.buildJpModeMap`. Paired with [JourneyPatternPlugin] in the facade
 * to resolve a JP's effective transport mode.
 */
internal class LineRoutePlugin : AbstractNetexPlugin() {

    private val _lineModes = linkedMapOf<String, Map<String, TransportMode>>()
    private val _routeToLine = linkedMapOf<String, Map<String, String>>()

    val lineModes: Map<String, Map<String, TransportMode>> get() = _lineModes
    val routeToLine: Map<String, Map<String, String>> get() = _routeToLine

    private var docLineModes: MutableMap<String, TransportMode> = linkedMapOf()
    private var docRouteToLine: MutableMap<String, String> = linkedMapOf()

    private enum class Container { LINE, ROUTE }
    private var container: Container? = null
    private var currentId: String? = null
    private var currentMode: TransportMode? = null
    private var currentLineRef: String? = null

    private var modeText: StringBuilder? = null

    override fun getName() = "LineRoutePlugin"
    override fun getDescription() = "Collects Line → mode and Route → LineRef maps"
    override fun getSupportedElementTypes(): Set<String> = setOf(
        "Line",
        "Route",
        "Line/TransportMode",
        "Line/VehicleMode",
        "Line/VehicleModes",
        "Route/LineRef",
    )

    override fun startElement(elementName: String, attributes: Attributes?, currentEntity: Entity?) {
        when (elementName) {
            "Line" -> {
                container = Container.LINE
                currentId = attributes?.getValue("id")
                currentMode = null
            }
            "Route" -> {
                container = Container.ROUTE
                currentId = attributes?.getValue("id")
                currentLineRef = null
            }
            "TransportMode", "VehicleMode", "VehicleModes" -> if (container == Container.LINE && currentMode == null) {
                modeText = StringBuilder()
            }
            "LineRef" -> if (container == Container.ROUTE && currentLineRef == null) {
                currentLineRef = attributes?.getValue("ref")
            }
        }
    }

    override fun characters(elementName: String, ch: CharArray?, start: Int, length: Int) {
        if (ch != null) modeText?.append(ch, start, length)
    }

    override fun endElement(elementName: String, currentEntity: Entity?) {
        when (elementName) {
            "TransportMode", "VehicleMode", "VehicleModes" -> {
                if (container == Container.LINE && currentMode == null) {
                    currentMode = parseTransportMode(modeText?.toString()?.trim())
                }
                modeText = null
            }
            "Line" -> {
                val id = currentId
                val mode = currentMode
                if (!id.isNullOrBlank() && mode != null) docLineModes[id] = mode
                container = null
                currentId = null
                currentMode = null
            }
            "Route" -> {
                val id = currentId
                val lineRef = currentLineRef
                if (!id.isNullOrBlank() && lineRef != null) docRouteToLine[id] = lineRef
                container = null
                currentId = null
                currentLineRef = null
            }
        }
    }

    override fun endDocument(documentName: String) {
        _lineModes[documentName] = docLineModes.toMap()
        _routeToLine[documentName] = docRouteToLine.toMap()
        docLineModes = mutableMapOf()
        docRouteToLine = mutableMapOf()
    }
}

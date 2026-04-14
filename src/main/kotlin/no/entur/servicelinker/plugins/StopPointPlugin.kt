package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.Coordinate
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import org.entur.netex.tools.lib.model.Entity
import org.entur.netex.tools.lib.plugin.AbstractNetexPlugin
import org.xml.sax.Attributes
import java.math.BigDecimal

/**
 * Mirrors `NetexProcessor.extractStopPoints` and `extractScheduledStopPointIdsAndVersions`.
 *
 * Produces per-document:
 *   - stopPoints:          List<StopPoint> — SSPs and StopPlaces that have coordinates.
 *   - stopIdsAndVersions:  Map<stopId, version> — every ScheduledStopPoint declaration,
 *                          even without coordinates; matches the DOM method's defaulting
 *                          ("1" if blank).
 *
 * Location guards mirror the DOM extraction shape:
 *   - For `ScheduledStopPoint`, coordinates are captured only inside a `Location` that is a
 *     descendant of the SSP.
 *   - For `StopPlace`, coordinates are captured only inside a `Location` that is inside a
 *     `Centroid` inside the StopPlace. A StopPlace lacking its own Centroid but containing
 *     a Quay with `Centroid/Location/...` resolves the same way as DOM's
 *     `getElementsByTagName("Centroid").item(0)` — first-in-document-order wins.
 */
internal class StopPointPlugin : AbstractNetexPlugin() {
    private val _stopPoints = mutableMapOf<String, List<StopPoint>>()
    private val _stopIds = mutableMapOf<String, Map<String, String>>()

    val stopPoints: Map<String, List<StopPoint>> get() = _stopPoints
    val stopIdsAndVersions: Map<String, Map<String, String>> get() = _stopIds

    private var docStops: MutableList<StopPoint> = mutableListOf()
    private var docIds: MutableMap<String, String> = mutableMapOf()

    private enum class Container { SSP, STOP_PLACE }

    private var container: Container? = null

    private var id: String? = null
    private var version: String? = null
    private var name: String? = null
    private var shortName: String? = null
    private var lon: BigDecimal? = null
    private var lat: BigDecimal? = null
    private var mode: TransportMode? = null

    // Location guard state. `insideLocation` is only true when we are inside a Location
    // the DOM code would accept for this container type.
    private var insideCentroid = false
    private var insideLocation = false

    // Character buffer for the currently-open leaf element, if any.
    private var text: StringBuilder? = null

    private enum class Target { NAME, SHORT_NAME, LONGITUDE, LATITUDE, MODE }

    private var target: Target? = null

    override fun getName() = "StopPointPlugin"

    override fun getDescription() = "Collects ScheduledStopPoint + StopPlace metadata"

    override fun getSupportedElementTypes(): Set<String> =
        setOf(
            "ScheduledStopPoint",
            "StopPlace",
            "ScheduledStopPoint/Name",
            "ScheduledStopPoint/ShortName",
            "ScheduledStopPoint/Location",
            "ScheduledStopPoint/Longitude",
            "ScheduledStopPoint/Latitude",
            "ScheduledStopPoint/TransportMode",
            "ScheduledStopPoint/VehicleMode",
            "ScheduledStopPoint/VehicleModes",
            "StopPlace/Name",
            "StopPlace/Centroid",
            "StopPlace/Location",
            "StopPlace/Longitude",
            "StopPlace/Latitude",
            "StopPlace/TransportMode",
            "StopPlace/VehicleMode",
            "StopPlace/VehicleModes",
        )

    override fun startElement(
        elementName: String,
        attributes: Attributes?,
        currentEntity: Entity?,
    ) {
        when (elementName) {
            "ScheduledStopPoint" -> {
                resetCurrent()
                container = Container.SSP
                id = attributes?.getValue("id")
                version = attributes?.getValue("version")
                id?.takeIf { it.isNotBlank() }?.let { docIds[it] = version?.ifBlank { null } ?: "1" }
            }

            "StopPlace" -> {
                resetCurrent()
                container = Container.STOP_PLACE
                id = attributes?.getValue("id")
                version = attributes?.getValue("version")
            }

            "Centroid" -> {
                if (container == Container.STOP_PLACE) insideCentroid = true
            }

            "Location" -> {
                when (container) {
                    Container.SSP -> {
                        insideLocation = true
                    }

                    Container.STOP_PLACE -> {
                        if (insideCentroid) insideLocation = true
                    }

                    null -> {}
                }
            }

            "Name" -> {
                startText(Target.NAME)
            }

            "ShortName" -> {
                startText(Target.SHORT_NAME)
            }

            "Longitude" -> {
                if (insideLocation) startText(Target.LONGITUDE)
            }

            "Latitude" -> {
                if (insideLocation) startText(Target.LATITUDE)
            }

            "TransportMode", "VehicleMode", "VehicleModes" -> {
                startText(Target.MODE)
            }
        }
    }

    override fun characters(
        elementName: String,
        ch: CharArray?,
        start: Int,
        length: Int,
    ) {
        if (ch != null) text?.append(ch, start, length)
    }

    override fun endElement(
        elementName: String,
        currentEntity: Entity?,
    ) {
        when (elementName) {
            "ScheduledStopPoint", "StopPlace" -> {
                emitIfComplete()
                container = null
                insideCentroid = false
                insideLocation = false
            }

            "Centroid" -> {
                if (container == Container.STOP_PLACE) insideCentroid = false
            }

            "Location" -> {
                insideLocation = false
            }

            "Name" -> {
                if (target == Target.NAME && name == null) name = text?.toString()?.trim()
                clearText()
            }

            "ShortName" -> {
                if (target == Target.SHORT_NAME && shortName == null) shortName = text?.toString()?.trim()
                clearText()
            }

            "Longitude" -> {
                if (target == Target.LONGITUDE && lon == null) lon = text?.toString()?.trim()?.toBigDecimalOrNull()
                clearText()
            }

            "Latitude" -> {
                if (target == Target.LATITUDE && lat == null) lat = text?.toString()?.trim()?.toBigDecimalOrNull()
                clearText()
            }

            "TransportMode", "VehicleMode", "VehicleModes" -> {
                if (target == Target.MODE && mode == null) mode = parseTransportMode(text?.toString()?.trim())
                clearText()
            }
        }
    }

    override fun endDocument(documentName: String) {
        _stopPoints[documentName] = docStops.toList()
        _stopIds[documentName] = docIds.toMap()
        docStops = mutableListOf()
        docIds = mutableMapOf()
        resetCurrent()
    }

    private fun startText(t: Target) {
        text = StringBuilder()
        target = t
    }

    private fun clearText() {
        text = null
        target = null
    }

    private fun resetCurrent() {
        container = null
        id = null
        version = null
        name = null
        shortName = null
        lon = null
        lat = null
        mode = null
        insideCentroid = false
        insideLocation = false
        text = null
        target = null
    }

    private fun emitIfComplete() {
        val theId = id ?: return
        val theLat = lat ?: return
        val theLon = lon ?: return
        val theName =
            when (container) {
                Container.SSP -> name ?: shortName

                // DOM parseStopPoint falls back to ShortName
                Container.STOP_PLACE -> name

                // DOM parseStopPlace does not
                null -> return
            }
        docStops.add(
            StopPoint(
                id = theId,
                name = theName,
                coordinate = Coordinate(theLat, theLon),
                transportMode = mode,
                version = version,
            ),
        )
    }
}

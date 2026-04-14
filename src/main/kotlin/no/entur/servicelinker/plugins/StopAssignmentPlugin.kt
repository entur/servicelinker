package no.entur.servicelinker.plugins

import org.entur.netex.tools.lib.model.Entity
import org.entur.netex.tools.lib.plugin.AbstractNetexPlugin
import org.xml.sax.Attributes

/**
 * Mirrors `NetexProcessor.extractStopAssignments`.
 * Produces per-document: Map<stopPointRef, quayRef>.
 */
internal class StopAssignmentPlugin : AbstractNetexPlugin() {
    private val _assignments = mutableMapOf<String, Map<String, String>>()
    val assignments: Map<String, Map<String, String>> get() = _assignments

    private var docAssignments: MutableMap<String, String> = mutableMapOf()
    private var currentStopRef: String? = null
    private var currentQuayRef: String? = null

    override fun getName() = "StopAssignmentPlugin"

    override fun getDescription() = "Collects PassengerStopAssignment (stop → quay) mappings"

    override fun getSupportedElementTypes(): Set<String> =
        setOf(
            "PassengerStopAssignment",
            "PassengerStopAssignment/ScheduledStopPointRef",
            "PassengerStopAssignment/QuayRef",
        )

    override fun startElement(
        elementName: String,
        attributes: Attributes?,
        currentEntity: Entity?,
    ) {
        when (elementName) {
            "PassengerStopAssignment" -> {
                currentStopRef = null
                currentQuayRef = null
            }

            "ScheduledStopPointRef" -> {
                if (currentStopRef == null) currentStopRef = attributes?.getValue("ref")
            }

            "QuayRef" -> {
                if (currentQuayRef == null) currentQuayRef = attributes?.getValue("ref")
            }
        }
    }

    override fun endElement(
        elementName: String,
        currentEntity: Entity?,
    ) {
        if (elementName == "PassengerStopAssignment") {
            val s = currentStopRef
            val q = currentQuayRef
            if (!s.isNullOrBlank() && !q.isNullOrBlank()) docAssignments[s] = q
            currentStopRef = null
            currentQuayRef = null
        }
    }

    override fun endDocument(documentName: String) {
        _assignments[documentName] = docAssignments.toMap()
        docAssignments = mutableMapOf()
    }
}

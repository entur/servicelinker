package no.entur.servicelinker.plugins

import org.entur.netex.tools.lib.model.Entity
import org.entur.netex.tools.lib.plugin.AbstractNetexPlugin
import org.xml.sax.Attributes

/**
 * Mirrors `NetexProcessor.extractExistingServiceLinks`.
 * Produces per-document: Map<"${fromRef}_${toRef}", serviceLinkId>.
 */
internal class ServiceLinkPlugin : AbstractNetexPlugin() {

    private val _serviceLinks = mutableMapOf<String, Map<String, String>>()
    val serviceLinks: Map<String, Map<String, String>> get() = _serviceLinks

    private var docLinks: MutableMap<String, String> = mutableMapOf()

    private var currentId: String? = null
    private var fromRef: String? = null
    private var toRef: String? = null

    override fun getName() = "ServiceLinkPlugin"
    override fun getDescription() = "Collects existing ServiceLink (fromRef, toRef) → id mappings"
    override fun getSupportedElementTypes(): Set<String> = setOf(
        "ServiceLink",
        "ServiceLink/FromPointRef",
        "ServiceLink/ToPointRef",
    )

    override fun startElement(elementName: String, attributes: Attributes?, currentEntity: Entity?) {
        when (elementName) {
            "ServiceLink" -> {
                currentId = attributes?.getValue("id")
                fromRef = null
                toRef = null
            }
            "FromPointRef" -> if (fromRef == null) fromRef = attributes?.getValue("ref")
            "ToPointRef" -> if (toRef == null) toRef = attributes?.getValue("ref")
        }
    }

    override fun endElement(elementName: String, currentEntity: Entity?) {
        if (elementName == "ServiceLink") {
            val id = currentId
            val f = fromRef
            val t = toRef
            if (id != null && !f.isNullOrBlank() && !t.isNullOrBlank()) {
                docLinks["${f}_${t}"] = id
            }
            currentId = null
            fromRef = null
            toRef = null
        }
    }

    override fun endDocument(documentName: String) {
        _serviceLinks[documentName] = docLinks.toMap()
        docLinks = mutableMapOf()
    }
}

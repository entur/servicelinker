package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.ExistingSlijpInfo
import no.entur.servicelinker.model.StopPoint
import no.entur.servicelinker.model.TransportMode
import org.entur.netex.tools.lib.NetexProcessor
import org.entur.netex.tools.lib.config.CliConfig
import org.entur.netex.tools.lib.config.FilterConfig
import org.springframework.stereotype.Service

/**
 * Facade that runs all Pass 1 plugins in a single `NetexProcessor.buildEntityModel` pass
 * and exposes the collected data in the same shapes `NetexProcessor` (servicelinker)
 * produces from DOM extraction.
 *
 * Each [extract] call builds fresh plugin instances, so the extractor is safe to reuse
 * across batches without state leaking between calls.
 *
 * The Pass 1 replacement for servicelinker's former DOM extraction code.
 */
@Service
class Pass1PluginExtractor {

    fun extract(documents: Map<String, ByteArray>): Pass1Results {
        val stopPointPlugin = StopPointPlugin()
        val stopAssignmentPlugin = StopAssignmentPlugin()
        val serviceLinkPlugin = ServiceLinkPlugin()
        val journeyPatternPlugin = JourneyPatternPlugin()
        val lineRoutePlugin = LineRoutePlugin()

        val filterConfig = FilterConfig(
            plugins = listOf(
                stopPointPlugin,
                stopAssignmentPlugin,
                serviceLinkPlugin,
                journeyPatternPlugin,
                lineRoutePlugin,
            )
        )
        NetexProcessor(CliConfig(), filterConfig).buildEntityModel(documents)

        val perDoc = documents.keys.associateWith { name ->
            DocResults(
                stopPoints = stopPointPlugin.stopPoints[name].orEmpty(),
                stopIdsAndVersions = stopPointPlugin.stopIdsAndVersions[name].orEmpty(),
                assignments = stopAssignmentPlugin.assignments[name].orEmpty(),
                existingServiceLinks = serviceLinkPlugin.serviceLinks[name].orEmpty(),
                stopSequences = journeyPatternPlugin.stopSequences[name].orEmpty(),
                existingSlijp = journeyPatternPlugin.existingSlijp[name].orEmpty(),
                jpModeMap = resolveJpModeMap(
                    journeyPatternPlugin.jpModeInputs[name].orEmpty(),
                    lineRoutePlugin.lineModes[name].orEmpty(),
                    lineRoutePlugin.routeToLine[name].orEmpty(),
                )
            )
        }
        return Pass1Results(perDoc)
    }

    fun extract(name: String, bytes: ByteArray): DocResults =
        extract(mapOf(name to bytes)).forDocument(name)

    companion object {
        /**
         * Resolve each JP's effective transport mode using direct mode → LineRef → RouteRef→Line.
         * Mirrors `NetexProcessor.buildJpModeMap` exactly: when a LineRef is present, the result
         * is whatever `lineModes[lineRef]` returns (possibly null) — RouteRef is only consulted
         * when the JP has no LineRef at all.
         */
        internal fun resolveJpModeMap(
            inputs: Map<String, JourneyPatternPlugin.JpModeInputs>,
            lineModes: Map<String, TransportMode>,
            routeToLine: Map<String, String>,
        ): Map<String, TransportMode?> {
            val out = LinkedHashMap<String, TransportMode?>(inputs.size)
            for ((jpId, mi) in inputs) {
                val resolved: TransportMode? = when {
                    mi.directMode != null -> mi.directMode
                    mi.lineRef != null -> lineModes[mi.lineRef]
                    mi.routeRef != null -> routeToLine[mi.routeRef]?.let { lineModes[it] }
                    else -> null
                }
                out[jpId] = resolved
            }
            return out
        }
    }
}

data class DocResults(
    val stopPoints: List<StopPoint>,
    val stopIdsAndVersions: Map<String, String>,
    val assignments: Map<String, String>,
    val existingServiceLinks: Map<String, String>,
    val stopSequences: Map<String, List<String>>,
    val existingSlijp: Map<String, Map<String, ExistingSlijpInfo>>,
    val jpModeMap: Map<String, TransportMode?>,
) {
    /**
     * Raw consecutive stop pairs across every JourneyPattern in the document, keyed by
     * `${fromRef}_${toRef}`. First-seen wins for duplicates so the returned mode matches
     * the JP that introduced the pair.
     */
    fun rawPairRefs(): Map<String, RawPairRef> {
        val out = LinkedHashMap<String, RawPairRef>()
        for ((jpId, refs) in stopSequences) {
            val mode = jpModeMap[jpId]
            for (i in 0 until refs.size - 1) {
                out.putIfAbsent("${refs[i]}_${refs[i + 1]}", RawPairRef(refs[i], refs[i + 1], mode))
            }
        }
        return out
    }
}

data class Pass1Results(val byDocument: Map<String, DocResults>) {
    fun forDocument(name: String): DocResults = byDocument[name]
        ?: error("No results for document: $name (have: ${byDocument.keys})")
}

/** Raw consecutive stop reference with the transport mode of the JP that introduced it. */
data class RawPairRef(
    val fromRef: String,
    val toRef: String,
    val mode: TransportMode?,
)

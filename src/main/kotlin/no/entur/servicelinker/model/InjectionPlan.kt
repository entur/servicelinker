package no.entur.servicelinker.model

data class InjectionPlan(
    /** New ServiceLink elements to inject into the serviceLinks section. */
    val newServiceLinks: List<ServiceLinkData>,
    /** Stub ScheduledStopPoint declarations to inject (id -> version). */
    val stubScheduledStopPoints: Map<String, String>,
    /** Pre-merged linksInSequence entries per JourneyPattern. Key = JP id. */
    val journeyPatternLinks: Map<String, List<SlijpEntry>>,
) {
    val isEmpty: Boolean get() =
        newServiceLinks.isEmpty() && journeyPatternLinks.isEmpty() && stubScheduledStopPoints.isEmpty()
}

data class ServiceLinkData(
    val id: String,
    val version: String,
    val distance: Int?,
    val projectionId: String,
    val gmlLineStringId: String,
    val posList: String,
    val fromPointRef: String,
    val fromPointVersion: String,
    val toPointRef: String,
    val toPointVersion: String
)

data class SlijpEntry(
    val id: String,
    val version: String,
    val order: Int,
    val serviceLinkRef: String,
    /** Null to omit version attribute on ServiceLinkRef (cross-file compatibility). */
    val serviceLinkRefVersion: String?
)

/** Metadata about an existing ServiceLinkInJourneyPattern, extracted from a DOM parse. */
data class ExistingSlijpInfo(
    val id: String,
    val version: String,
    val serviceLinkRefVersion: String?
)

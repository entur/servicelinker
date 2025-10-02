package no.entur.servicelinker.model

data class ServiceLink(
    val id: String,
    val fromStopPointRef: String,
    val toStopPointRef: String,
    val distance: Int?,
    val linkSequenceProjection: LinkSequenceProjection?
)

data class LinkSequenceProjection(
    val coordinates: List<Coordinate>
)

data class StopPoint(
    val id: String,
    val name: String?,
    val coordinate: Coordinate,
    val transportMode: TransportMode?,
    val version: String?
)

enum class TransportMode {
    BUS, TRAM, RAIL, METRO, FERRY, COACH, UNKNOWN
}

data class StationPair(
    val from: StopPoint,
    val to: StopPoint
) {
    fun generateServiceLinkId(prefix: String): String {
        return "${prefix}${from.id}_${to.id}".replace(":", "_")
    }
}

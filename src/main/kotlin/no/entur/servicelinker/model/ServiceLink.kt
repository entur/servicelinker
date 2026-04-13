package no.entur.servicelinker.model

data class StopPoint(
    val id: String,
    val name: String?,
    val coordinate: Coordinate,
    val transportMode: TransportMode?,
    val version: String?
)

enum class TransportMode {
    BUS, TRAM, RAIL, METRO, FERRY, WATER, COACH, UNKNOWN
}

data class StationPair(
    val from: StopPoint,
    val to: StopPoint,
    val transportMode: TransportMode? = null
)

/** A stop pair together with its (optional) OSRM-routed geometry. */
data class RoutedPair(
    val stationPair: StationPair,
    val route: OSRMRoute?,
)

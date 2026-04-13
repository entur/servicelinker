package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.TransportMode

/**
 * Parse a raw TransportMode / VehicleMode / VehicleModes text value into our enum.
 *
 * The plural `VehicleModes` element is list-valued (space-separated tokens per the NeTEx
 * profile). We take the first token — downstream only needs one routing mode per stop, line,
 * or journey pattern.
 */
internal fun parseTransportMode(raw: String?): TransportMode? {
    val first = raw?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.firstOrNull()
    return when (first?.uppercase()) {
        null, "" -> null
        "BUS" -> TransportMode.BUS
        "TRAM" -> TransportMode.TRAM
        "RAIL" -> TransportMode.RAIL
        "METRO" -> TransportMode.METRO
        "FERRY" -> TransportMode.FERRY
        "WATER" -> TransportMode.WATER
        "COACH" -> TransportMode.COACH
        else -> TransportMode.UNKNOWN
    }
}

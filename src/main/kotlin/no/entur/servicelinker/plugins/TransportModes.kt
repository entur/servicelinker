package no.entur.servicelinker.plugins

import no.entur.servicelinker.model.TransportMode

/**
 * Parse a raw TransportMode/VehicleMode text value into our enum. Mirrors the mapping
 * used by `NetexProcessor.extractTransportMode` so plugin extraction produces identical
 * enum values to the DOM path.
 */
internal fun parseTransportMode(raw: String?): TransportMode? = when (raw?.uppercase()) {
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

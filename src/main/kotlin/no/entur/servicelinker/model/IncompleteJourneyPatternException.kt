package no.entur.servicelinker.model

data class MissingPair(
    val jpId: String,
    val fromRef: String,
    val toRef: String,
)

class IncompleteJourneyPatternException(
    val missingPairs: List<MissingPair>,
    message: String,
) : RuntimeException(message)

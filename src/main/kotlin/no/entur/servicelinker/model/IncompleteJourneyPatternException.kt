package no.entur.servicelinker.model

data class MissingPair(
    val jpId: String,
    val fromRef: String,
    val toRef: String,
)

class IncompleteJourneyPatternException(
    val missingPairs: List<MissingPair>,
    message: String,
    /**
     * True when at least one unresolved quay failed transiently (NSR 429/5xx/timeout) rather than
     * being genuinely absent from NSR. Such a run is worth retrying -- the coordinate cache only
     * holds successes, so a retry starts warmer and under lower load. A deterministic failure
     * (quays really missing) is not.
     */
    val retryable: Boolean = false,
) : RuntimeException(message)

package no.entur.servicelinker.service

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * Counters for the failure modes that used to be visible only in the logs. NOR-WAY spotted the
 * September 2026 straight-line regression before we did, which is what these are for.
 *
 * Codespace comes from the MDC set by PubSubMessageHandler; cardinality is bounded by the number
 * of datasets marduk feeds us.
 */
@Component
class ServicelinkerMetrics(
    private val registry: MeterRegistry,
) {
    /** NSR answered 429. Rising here precedes datasets failing on unresolved quays. */
    fun nsrThrottled() = registry.counter("servicelinker.nsr.throttled", "codespace", codespace()).increment()

    /** A quay we could not resolve. `reason`: absent, exhausted, deadline, pool, auth, parse, aborted. */
    fun nsrLookupFailed(reason: String) =
        registry.counter("servicelinker.nsr.lookup.failed", "reason", reason, "codespace", codespace()).increment()

    /** A ServiceLink emitted as a straight line instead of a routed geometry. */
    fun straightLineFallback(
        mode: String?,
        reason: String,
    ) = registry
        .counter(
            "servicelinker.servicelink.straight_line",
            "mode",
            mode ?: "UNKNOWN",
            "reason",
            reason,
            "codespace",
            codespace(),
        ).increment()

    /** Terminal dataset outcome, one per dataset. `status`: success, failed. */
    fun datasetOutcome(status: String) =
        registry.counter("servicelinker.dataset.outcome", "status", status, "codespace", codespace()).increment()

    /** A processing attempt that will be retried. Not terminal, so kept out of [datasetOutcome]. */
    fun datasetRetry() = registry.counter("servicelinker.dataset.retry", "codespace", codespace()).increment()

    /**
     * The referential is producer-supplied and Micrometer never evicts a meter, so an unexpected
     * value would grow the registry for good. Anything not codespace-shaped becomes "unknown".
     */
    private fun codespace(): String = MDC.get("codespace")?.takeIf { CODESPACE.matches(it) } ?: "unknown"

    private companion object {
        val CODESPACE = Regex("[a-zA-Z0-9_-]{1,20}")
    }
}

package no.entur.servicelinker.service

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.stereotype.Component

/**
 * Counters for the failure modes that used to be visible only in the logs.
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

    /** A quay we could not resolve. `reason`: absent, exhausted, deadline, pool, aborted. */
    fun nsrLookupFailed(reason: String) =
        registry.counter("servicelinker.nsr.lookup.failed", "reason", reason, "codespace", codespace()).increment()

    /** Terminal dataset outcome. `status`: success, failed, retried. */
    fun datasetOutcome(status: String) =
        registry.counter("servicelinker.dataset.outcome", "status", status, "codespace", codespace()).increment()

    private fun codespace(): String = MDC.get("codespace") ?: "unknown"
}

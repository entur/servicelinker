package no.entur.servicelinker.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import no.entur.servicelinker.model.Coordinate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.io.StringReader
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Tunables for the NSR coordinate-lookup client. Override at runtime with environment variables
 * (Spring Boot relaxed binding): e.g. `NSR_MAX_CONCURRENT_REQUESTS=16` or `NSR_MAX_RETRIES=2`.
 */
@ConfigurationProperties(prefix = "nsr")
data class NsrProperties(
    /** NSR read endpoint base URL. */
    val baseUrl: String = "https://api.entur.io/stop-places/v1/read",
    /** Per-HTTP-call response timeout (ms). */
    val timeout: Long = 10_000,
    /** Whether to cache resolved coordinates in-process. */
    val cacheCoordinates: Boolean = true,
    /** Max entries in the in-process coordinate cache (LRU + 24h TTL by default). */
    val cacheMaxSize: Int = 50_000,
    /** Cache entry lifetime. */
    val cacheTtlHours: Int = 6,
    /**
     * Bounds the parallel NSR fetches and the Netty pool *process-wide*, not per batch: marduk
     * kicks several codespaces off within seconds of each other, so a per-batch bound multiplied
     * by the number of datasets in flight. That burst drove NSR to rate-limit us (HTTP 429), which
     * exhausted the per-quay retries and failed the whole dataset over a handful of unresolved
     * stops. Lower further if 429s persist (note it is still per pod, so the cluster-wide ceiling
     * is this times the replica count).
     *
     * 4 x 2 replicas = 8 in flight cluster-wide, which is the level a lone dataset ran at cleanly
     * (vyx: 1783 quays in 6.7s, zero failures) before the September 2026 nwy failure.
     */
    val maxConcurrentRequests: Int = 4,
    /**
     * Total attempts per quay (1 = no retry). Each retry waits [retryBaseDelayMs]‥[retryMaxDelayMs].
     * Sized to outlast a brief NSR rate-limit window rather than give up inside it. The effective
     * attempt count is also bounded by [perRequestDeadlineMs].
     */
    val maxRetries: Int = 6,
    /** Lower bound for the decorrelated-jitter retry delay. */
    val retryBaseDelayMs: Long = 500,
    /** Upper bound for the decorrelated-jitter retry delay; also caps a server-supplied `Retry-After`. */
    val retryMaxDelayMs: Long = 10_000,
    /**
     * Hard upper bound on time spent on a single quay across all retries. Set to 0 to disable
     * (test-only — `kotlinx-coroutines-test`'s `runTest` fires `withTimeout` against virtual time,
     * which would otherwise pre-empt real-time I/O).
     */
    val perRequestDeadlineMs: Long = 45_000,
)

/**
 * Coordinates resolved for a batch, plus the quays whose lookup failed in a way that a later run
 * could plausibly succeed at (429/5xx/network/deadline) as opposed to a definitive negative (404).
 */
data class NsrLookupResult(
    val coordinates: Map<String, Coordinate>,
    val transientFailures: Set<String>,
)

@Service
class NsrService(
    private val nsrProperties: NsrProperties,
    private val meters: ServicelinkerMetrics = ServicelinkerMetrics(SimpleMeterRegistry()),
) {
    private val logger = LoggerFactory.getLogger(NsrService::class.java)

    /** Process-wide, not per batch — see [NsrProperties.maxConcurrentRequests]. */
    private val semaphore = Semaphore(nsrProperties.maxConcurrentRequests)

    private val coordinateCache: Cache<String, Coordinate> =
        Caffeine
            .newBuilder()
            .maximumSize(nsrProperties.cacheMaxSize.toLong())
            .expireAfterWrite(nsrProperties.cacheTtlHours.toLong(), TimeUnit.HOURS)
            .build()

    private val webClient: WebClient by lazy {
        // Size the Netty pool to match the semaphore so coroutines don't queue inside the pool
        // (where the response timeout doesn't apply and our retry logic can't see waits).
        // The pending-acquire timeout is bounded by perRequestDeadlineMs (the caller's hard
        // deadline) — using nsr.timeout here would leave ~500ms for the HTTP after a queued wait.
        val acquireTimeoutMs =
            if (nsrProperties.perRequestDeadlineMs > 0) nsrProperties.perRequestDeadlineMs else nsrProperties.timeout
        val connectionProvider =
            ConnectionProvider
                .builder("nsr")
                .maxConnections(nsrProperties.maxConcurrentRequests)
                .pendingAcquireMaxCount(nsrProperties.maxConcurrentRequests * 2)
                .pendingAcquireTimeout(Duration.ofMillis(acquireTimeoutMs))
                .build()
        WebClient
            .builder()
            .baseUrl(nsrProperties.baseUrl)
            // https://developer.entur.org — header must be "company-application"
            .defaultHeader("ET-Client-Name", ET_CLIENT_NAME)
            .defaultHeader("Accept", "application/xml")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create(connectionProvider)
                        .followRedirect(true)
                        .responseTimeout(Duration.ofMillis(nsrProperties.timeout)),
                ),
            ).build()
    }

    /** Single-quay convenience wrapper. */
    suspend fun getQuayCoordinates(quayId: String): Coordinate? = getQuayCoordinates(listOf(quayId))[quayId]

    /** Coordinates only — use [lookupQuayCoordinates] when the failure cause matters. */
    suspend fun getQuayCoordinates(quayIds: Collection<String>): Map<String, Coordinate> =
        lookupQuayCoordinates(quayIds).coordinates

    /**
     * Fetch coordinates for many quays in parallel with bounded concurrency. Quays absent from
     * [NsrLookupResult.coordinates] either don't exist in NSR (404) or failed after retries;
     * [NsrLookupResult.transientFailures] names the latter so the caller can decide to retry.
     */
    suspend fun lookupQuayCoordinates(quayIds: Collection<String>): NsrLookupResult {
        if (quayIds.isEmpty()) return NsrLookupResult(emptyMap(), emptySet())
        val results = java.util.concurrent.ConcurrentHashMap<String, Coordinate>()
        val transient = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val toFetch = mutableListOf<String>()

        for (id in quayIds.distinct()) {
            val cached = if (nsrProperties.cacheCoordinates) coordinateCache.getIfPresent(id) else null
            if (cached != null) {
                results[id] = cached
            } else {
                toFetch.add(id)
            }
        }
        val cacheHits = results.size
        if (toFetch.isEmpty()) return NsrLookupResult(results, emptySet())

        logger.info(
            "NSR batch lookup: {} unique quays ({} cache hits, {} to fetch, concurrency={})",
            results.size + toFetch.size,
            cacheHits,
            toFetch.size,
            nsrProperties.maxConcurrentRequests,
        )
        val start = System.currentTimeMillis()
        // Quays that failed transiently then succeeded on retry: a leading indicator that the retry
        // budget is absorbing NSR rate-limiting, before exhausted retries start failing datasets.
        val retriedSuccesses = AtomicInteger(0)
        coroutineScope {
            toFetch
                .map { id ->
                    async {
                        semaphore.withPermit {
                            when (val outcome = fetchWithDeadlineAndRetries(id, retriedSuccesses)) {
                                is QuayResult.Resolved -> {
                                    if (nsrProperties.cacheCoordinates) coordinateCache.put(id, outcome.coord)
                                    results[id] = outcome.coord
                                }

                                is QuayResult.Absent -> Unit
                                is QuayResult.Transient -> transient.add(id)
                            }
                        }
                    }
                }.awaitAll()
        }
        val elapsed = System.currentTimeMillis() - start
        val fetched = results.size - cacheHits
        val failed = toFetch.size - fetched
        logger.info(
            "NSR batch lookup complete in {}ms: {} fetched ({} after retry), {} failed ({} transient), {} cached",
            elapsed,
            fetched,
            retriedSuccesses.get(),
            failed,
            transient.size,
            cacheHits,
        )
        return NsrLookupResult(results, transient.toSet())
    }

    /** Per-quay total deadline caps the worst-case retry budget
     *  (maxRetries × responseTimeout + retryMaxDelayMs × (maxRetries-1)) at
     *  [NsrProperties.perRequestDeadlineMs], so one slow quay can't hold a semaphore permit for the
     *  whole budget. Set [NsrProperties.perRequestDeadlineMs] to 0 to disable (useful in tests where
     *  `runTest`'s virtual time scheduler fires `withTimeout` ahead of real-time I/O). */
    private suspend fun fetchWithDeadlineAndRetries(
        quayId: String,
        retriedSuccesses: AtomicInteger,
    ): QuayResult {
        if (nsrProperties.perRequestDeadlineMs <= 0) return fetchWithRetries(quayId, retriedSuccesses)
        return try {
            withTimeout(nsrProperties.perRequestDeadlineMs) { fetchWithRetries(quayId, retriedSuccesses) }
        } catch (e: TimeoutCancellationException) {
            logger.warn(
                "Failed to get coordinates for quay {}: per-request deadline exceeded ({}ms)",
                quayId,
                nsrProperties.perRequestDeadlineMs,
            )
            meters.nsrLookupFailed("deadline")
            QuayResult.Transient("per-request deadline exceeded")
        }
    }

    /** Terminal per-quay outcome, from the caller's point of view. */
    private sealed interface QuayResult {
        data class Resolved(
            val coord: Coordinate,
        ) : QuayResult

        /** Definitive negative: 404, an unparseable body, or a status we won't retry. */
        data object Absent : QuayResult

        /** Gave up while the failure still looked transient — worth trying again later. */
        data class Transient(
            val reason: String,
        ) : QuayResult
    }

    /** Outcome of a single HTTP attempt — classified so `fetchWithRetries` can stay flat. */
    private sealed class Attempt {
        /** Got a definitive answer: coords or null (404 / parse-empty). */
        data class Done(
            val coord: Coordinate?,
        ) : Attempt()

        /** Transient — try again after [delayHintMs] if any (e.g. server-supplied `Retry-After`). */
        data class Retry(
            val reason: String,
            val delayHintMs: Long?,
        ) : Attempt()

        /**
         * Stop now. [transient] separates load-induced aborts (pool exhaustion — retrying in the
         * same batch only makes it worse, but a later run would likely succeed) from permanent
         * ones (4xx we won't retry).
         */
        data class Abort(
            val reason: String,
            val transient: Boolean = false,
        ) : Attempt()
    }

    /**
     * Fetch one quay with retries on transient failures (429/5xx/network).
     * Returns null for 404 or after retries are exhausted.
     */
    private suspend fun fetchWithRetries(
        quayId: String,
        retriedSuccesses: AtomicInteger,
    ): QuayResult {
        var prevDelay = 0L
        var lastReason: String? = null
        for (attempt in 0 until nsrProperties.maxRetries) {
            when (val outcome = fetchOnce(quayId)) {
                is Attempt.Done -> {
                    if (attempt > 0 && outcome.coord != null) retriedSuccesses.incrementAndGet()
                    if (outcome.coord == null) meters.nsrLookupFailed("absent")
                    return outcome.coord?.let { QuayResult.Resolved(it) } ?: QuayResult.Absent
                }

                is Attempt.Abort -> {
                    logger.warn("Quay {} {} — not retrying", quayId, outcome.reason)
                    meters.nsrLookupFailed(if (outcome.transient) "pool" else "aborted")
                    return if (outcome.transient) QuayResult.Transient(outcome.reason) else QuayResult.Absent
                }

                is Attempt.Retry -> {
                    lastReason = outcome.reason
                    if (attempt == nsrProperties.maxRetries - 1) break
                    prevDelay = backoffDelayMs(attempt, prevDelay, outcome.delayHintMs)
                    logger.debug("Quay {} {} (attempt {}), retrying in {}ms", quayId, outcome.reason, attempt + 1, prevDelay)
                    delay(prevDelay)
                }
            }
        }
        logger.warn("Failed to get coordinates for quay {} after {} attempts: {}", quayId, nsrProperties.maxRetries, lastReason)
        meters.nsrLookupFailed("exhausted")
        return QuayResult.Transient(lastReason ?: "retries exhausted")
    }

    /** One HTTP attempt, classified. Pure function of the response/exception — no side effects. */
    private suspend fun fetchOnce(quayId: String): Attempt =
        try {
            val body =
                webClient
                    .get()
                    .uri("/quays/{quayId}", quayId)
                    .retrieve()
                    .awaitBody<String>()
            Attempt.Done(parseCoordinateFromXml(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebClientResponseException) {
            val status = e.statusCode.value()
            when {
                status == 404 -> {
                    Attempt.Done(null)
                }

                status == 429 || status >= 500 -> {
                    if (status == 429) meters.nsrThrottled()
                    Attempt.Retry("got HTTP $status", parseRetryAfterMs(e.headers.getFirst("Retry-After")))
                }

                else -> {
                    Attempt.Abort("got HTTP $status")
                }
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            // Pool exhaustion is self-inflicted queueing: retrying makes it worse and burns budget
            // for requests that would otherwise succeed. The caller's per-request deadline is the
            // right backstop.
            if (isPoolExhaustion(e)) {
                Attempt.Abort("pool acquire failed ($reason)", transient = true)
            } else {
                Attempt.Retry(reason, null)
            }
        }

    /**
     * Decorrelated jitter — `delay = min(cap, random(base, prev * 3))`. Spreads load better than
     * full jitter under sustained 429s/timeouts since it doesn't keep returning to ~0ms.
     * See AWS Architecture Blog: "Exponential Backoff and Jitter".
     */
    private fun backoffDelayMs(
        attempt: Int,
        prevDelayMs: Long,
        retryAfterMs: Long?,
    ): Long {
        if (retryAfterMs != null) return min(retryAfterMs, nsrProperties.retryMaxDelayMs)
        val base = nsrProperties.retryBaseDelayMs
        val upper =
            if (attempt == 0) base * 3 else max(base, prevDelayMs) * 3
        val capped = min(upper, nsrProperties.retryMaxDelayMs)
        return Random.nextLong(base, capped + 1)
    }

    /**
     * Reactor Netty signals connection-pool exhaustion via `PoolAcquireTimeoutException` and
     * `PoolAcquirePendingLimitException` (under `reactor.pool.*`). Match by simple class name to
     * avoid a compile-time dependency on those internal-ish types.
     */
    private fun isPoolExhaustion(e: Throwable): Boolean =
        generateSequence(e as Throwable?) { it.cause?.takeUnless { c -> c === it } }
            .any { it.javaClass.simpleName in POOL_EXHAUSTION_EXCEPTIONS }

    private fun parseRetryAfterMs(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // Retry-After can be seconds (most common) or HTTP-date (rare). Only handle seconds.
        return header.trim().toLongOrNull()?.let { it * 1000 }
    }

    private fun parseCoordinateFromXml(xml: String): Coordinate? =
        try {
            val reader = xmlInputFactory.createXMLStreamReader(StringReader(xml))
            var latitude: java.math.BigDecimal? = null
            var longitude: java.math.BigDecimal? = null
            var inCentroid = false
            var inLocation = false

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        when (reader.localName) {
                            "Centroid" -> inCentroid = true
                            "Location" -> if (inCentroid) inLocation = true
                            "Latitude" -> if (inLocation) latitude = reader.elementText.trim().toBigDecimalOrNull()
                            "Longitude" -> if (inLocation) longitude = reader.elementText.trim().toBigDecimalOrNull()
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
                            "Centroid" -> inCentroid = false
                            "Location" -> inLocation = false
                        }
                    }
                }
                if (latitude != null && longitude != null) break
            }
            reader.close()

            if (latitude != null && longitude != null) Coordinate(latitude, longitude) else null
        } catch (e: Exception) {
            logger.warn("Failed to parse NSR XML response: {}", e.message)
            null
        }

    fun getCacheSize(): Long = coordinateCache.estimatedSize()

    private companion object {
        const val ET_CLIENT_NAME = "entur-servicelinker"
        val POOL_EXHAUSTION_EXCEPTIONS = setOf("PoolAcquireTimeoutException", "PoolAcquirePendingLimitException")
        val xmlInputFactory: XMLInputFactory = XMLInputFactory.newInstance()
    }
}

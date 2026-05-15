package no.entur.servicelinker.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
    val cacheTtlHours: Int = 24,
    /** Bounds the parallel NSR fetches and the Netty pool. Lower if NSR rate-limits the client. */
    val maxConcurrentRequests: Int = 32,
    /** Total attempts per quay (1 = no retry). Each retry waits [retryBaseDelayMs]‥[retryMaxDelayMs]. */
    val maxRetries: Int = 4,
    /** Lower bound for the decorrelated-jitter retry delay. */
    val retryBaseDelayMs: Long = 500,
    /** Upper bound for the decorrelated-jitter retry delay; also caps a server-supplied `Retry-After`. */
    val retryMaxDelayMs: Long = 10_000,
    /**
     * Hard upper bound on time spent on a single quay across all retries. Set to 0 to disable
     * (test-only — `kotlinx-coroutines-test`'s `runTest` fires `withTimeout` against virtual time,
     * which would otherwise pre-empt real-time I/O).
     */
    val perRequestDeadlineMs: Long = 30_000,
)

@Service
class NsrService(
    private val nsrProperties: NsrProperties,
) {
    private val logger = LoggerFactory.getLogger(NsrService::class.java)

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

    /**
     * Fetch coordinates for many quays in parallel with bounded concurrency. Returns a map of
     * quay IDs that resolved successfully — IDs absent from the result either don't exist in NSR
     * (404) or failed after retries.
     */
    suspend fun getQuayCoordinates(quayIds: Collection<String>): Map<String, Coordinate> {
        if (quayIds.isEmpty()) return emptyMap()
        val results = java.util.concurrent.ConcurrentHashMap<String, Coordinate>()
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
        if (toFetch.isEmpty()) return results

        logger.info(
            "NSR batch lookup: {} unique quays ({} cache hits, {} to fetch, concurrency={})",
            results.size + toFetch.size,
            cacheHits,
            toFetch.size,
            nsrProperties.maxConcurrentRequests,
        )
        val start = System.currentTimeMillis()
        val semaphore = Semaphore(nsrProperties.maxConcurrentRequests)
        coroutineScope {
            toFetch
                .map { id ->
                    async {
                        semaphore.withPermit {
                            val coord = fetchWithDeadlineAndRetries(id)
                            if (coord != null) {
                                if (nsrProperties.cacheCoordinates) coordinateCache.put(id, coord)
                                results[id] = coord
                            }
                        }
                    }
                }.awaitAll()
        }
        val elapsed = System.currentTimeMillis() - start
        val fetched = results.size - cacheHits
        val failed = toFetch.size - fetched
        logger.info(
            "NSR batch lookup complete in {}ms: {} fetched, {} failed, {} cached",
            elapsed,
            fetched,
            failed,
            cacheHits,
        )
        return results
    }

    /** Per-quay total deadline guards against one slow quay holding a semaphore permit for ~70s
     *  (= maxRetries × responseTimeout + retryMaxDelayMs × (maxRetries-1)).
     *  Set [NsrProperties.perRequestDeadlineMs] to 0 to disable (useful in tests where
     *  `runTest`'s virtual time scheduler fires `withTimeout` ahead of real-time I/O). */
    private suspend fun fetchWithDeadlineAndRetries(quayId: String): Coordinate? {
        if (nsrProperties.perRequestDeadlineMs <= 0) return fetchWithRetries(quayId)
        return try {
            withTimeout(nsrProperties.perRequestDeadlineMs) { fetchWithRetries(quayId) }
        } catch (e: TimeoutCancellationException) {
            logger.warn(
                "Failed to get coordinates for quay {}: per-request deadline exceeded ({}ms)",
                quayId,
                nsrProperties.perRequestDeadlineMs,
            )
            null
        }
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

        /** Permanent or non-retryable for this client — stop now. */
        data class Abort(
            val reason: String,
        ) : Attempt()
    }

    /**
     * Fetch one quay with retries on transient failures (429/5xx/network).
     * Returns null for 404 or after retries are exhausted.
     */
    private suspend fun fetchWithRetries(quayId: String): Coordinate? {
        var prevDelay = 0L
        var lastReason: String? = null
        for (attempt in 0 until nsrProperties.maxRetries) {
            when (val outcome = fetchOnce(quayId)) {
                is Attempt.Done -> {
                    return outcome.coord
                }

                is Attempt.Abort -> {
                    logger.warn("Quay {} {} — not retrying", quayId, outcome.reason)
                    return null
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
        return null
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
            if (isPoolExhaustion(e)) Attempt.Abort("pool acquire failed ($reason)") else Attempt.Retry(reason, null)
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

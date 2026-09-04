package no.entur.servicelinker.controller

import com.google.cloud.spring.pubsub.core.PubSubTemplate
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage
import com.google.cloud.spring.pubsub.support.GcpPubSubHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import no.entur.servicelinker.model.IncompleteJourneyPatternException
import no.entur.servicelinker.service.ServiceLinkProducerService
import no.entur.servicelinker.service.ServicelinkerMetrics
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class PubSubMessageHandler(
    private val serviceLinkProducerService: ServiceLinkProducerService,
    private val pubSubTemplate: PubSubTemplate,
    @Value("\${servicelinker.pubsub.outbound.topic}") private val outboundTopic: String,
    @Value("\${gcs.bucket.input}") private val inputBucket: String,
    private val meters: ServicelinkerMetrics,
    // NSR rate-limiting comes from several codespaces starting within seconds of each other.
    // 2s doesn't outlast that; 30s and 60s do. Overridable so tests don't sleep for a minute.
    @Value("\${servicelinker.retry.throttled-delay-ms:30000}")
    private val throttledRetryDelayMs: Long = THROTTLED_RETRY_DELAY_MS,
) {
    private val logger = LoggerFactory.getLogger(PubSubMessageHandler::class.java)

    @ServiceActivator(inputChannel = "servicelinkerInputChannel")
    fun handleMessage(message: Message<*>) {
        val acker = message.headers[GcpPubSubHeaders.ORIGINAL_MESSAGE] as? BasicAcknowledgeablePubsubMessage
        val attributes = acker?.pubsubMessage?.attributesMap ?: emptyMap()

        val correlationId = attributes[CORRELATION_ID] ?: "unknown"
        val datasetReferential = attributes[DATASET_REFERENTIAL] ?: "unknown"
        val modesFilter: Set<String>? =
            attributes[SERVICE_LINK_MODES_HEADER]?.let { value ->
                if (value.isBlank()) emptySet() else value.split(",").map { it.trim().uppercase() }.toSet()
            }

        MDC.put("correlationId", correlationId)
        MDC.put("codespace", datasetReferential)
        MDC.put("stage", "incoming")
        try {
            val targetFileHandle =
                attributes[TARGET_FILE_HANDLE]
                    ?: throw IllegalArgumentException("Message missing 'RutebankenTargetFileHandle' attribute")

            logger.info("Received PubSub message: file={}, modesFilter={}", targetFileHandle, modesFilter)
            val startTime = System.currentTimeMillis()

            MDC.put("stage", "outgoing/started")
            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() +
                    mapOf(
                        LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_STARTED,
                        emitTimeAttribute(),
                    ),
            )
            logger.info("Published STARTED status")

            MDC.put("stage", "processing")
            val addedCount = processWithRetry(inputBucket, targetFileHandle, modesFilter)

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
            MDC.put("stage", "outgoing/success")
            logger.info(
                "Processing complete in {}s, added {} ServiceLinks. Publishing SUCCESS status.",
                String.format("%.1f", elapsedSec),
                addedCount,
            )

            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() +
                    mapOf(
                        LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_SUCCEEDED,
                        LINKED_NETEX_FILE_PATH_HEADER to targetFileHandle,
                        "addedServiceLinksCount" to addedCount.toString(),
                        emitTimeAttribute(),
                    ),
            )
            logger.info("Published SUCCESS status")
            meters.datasetOutcome("success")

            acker?.ack()
            logger.info("Message acknowledged")
        } catch (e: Throwable) {
            MDC.put("stage", "outgoing/failed")
            logger.error("Failed to process PubSub message: {}", e.message, e)
            meters.datasetOutcome("failed")
            try {
                // GCP PubSub caps attribute values at 1024 bytes
                // (https://cloud.google.com/pubsub/quotas#resource_limits). The integrity-check
                // message is multi-line for log readability — take the first line, then truncate.
                // The full message is preserved in our own logs via the error() call above.
                val headerMessage = (e.message ?: "Unknown error").substringBefore('\n').take(1024)
                pubSubTemplate.publish(
                    outboundTopic,
                    "",
                    attributes.toMap() +
                        mapOf(
                            LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_FAILED,
                            LINKING_ERROR_CODE_HEADER to headerMessage,
                            emitTimeAttribute(),
                        ),
                )
                logger.info("Published FAILED status")
            } catch (publishEx: Throwable) {
                logger.error("Failed to publish FAILED status: {}", publishEx.message)
            }
            acker?.ack()
            logger.info("Message acknowledged after failure")
        } finally {
            MDC.clear()
        }
    }

    // Stamped per call so STARTED and the terminal status carry distinct emit times (they are
    // separated by the actual processing duration). marduk orders the relayed JobEvents by this.
    private fun emitTimeAttribute(): Pair<String, String> = LINKING_STATUS_EVENT_TIME_HEADER to Instant.now().toString()

    private fun processWithRetry(
        bucketName: String,
        objectName: String,
        modesFilter: Set<String>?,
    ): Int {
        var lastException: Exception? = null
        var nsrThrottled = false
        repeat(MAX_RETRIES) { attempt ->
            logger.info("Starting processing attempt ${attempt + 1}/$MAX_RETRIES for gs://$bucketName/$objectName")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val mdcSnapshot = MDC.getCopyOfContextMap() ?: emptyMap()
                val future =
                    executor.submit(
                        Callable {
                            MDC.setContextMap(mdcSnapshot)
                            try {
                                runBlocking(MDCContext()) {
                                    serviceLinkProducerService.processDataset(bucketName, objectName, modesFilter)
                                }
                            } finally {
                                MDC.clear()
                            }
                        },
                    )
                return future.get(PROCESSING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Don't retry timeouts — the same input will almost certainly time out again
                throw RuntimeException(
                    "Processing timed out after ${PROCESSING_TIMEOUT_MS / 1000}s for gs://$bucketName/$objectName",
                    e,
                )
            } catch (e: Exception) {
                // future.get wraps task exceptions in ExecutionException, so unwrap once.
                val rootCause = e.cause ?: e
                if (rootCause is IncompleteJourneyPatternException && !rootCause.retryable) {
                    // Deterministic failure: the quays are genuinely absent from NSR, so the same
                    // input + same NSR cache → same outcome. Retrying would just triple the log
                    // noise and the NSR-batch traffic on rollover. A run that failed because NSR
                    // throttled us is a different animal — it falls through to the retry below.
                    throw rootCause
                }
                lastException = rootCause as? Exception ?: e
                nsrThrottled = rootCause is IncompleteJourneyPatternException && rootCause.retryable
                logger.warn("Attempt ${attempt + 1}/$MAX_RETRIES failed for gs://$bucketName/$objectName: ${rootCause.message}")
            } finally {
                executor.shutdownNow()
            }
            if (attempt < MAX_RETRIES - 1) {
                // Back off much harder after NSR throttling: the retry is only worth anything once
                // the concurrent datasets that caused the burst have drained.
                val base = if (nsrThrottled) throttledRetryDelayMs else RETRY_DELAY_MS
                logger.info("Retrying in {}ms (nsrThrottled={})", base * (attempt + 1), nsrThrottled)
                meters.datasetRetry()
                Thread.sleep(base * (attempt + 1))
            }
        }
        throw lastException ?: RuntimeException("Failed after $MAX_RETRIES attempts")
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        const val THROTTLED_RETRY_DELAY_MS = 30_000L
        const val PROCESSING_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes

        // Matches Constants.java in marduk
        const val LINKING_NETEX_FILE_STATUS_HEADER: String = "LinkingStatus"
        const val LINKING_NETEX_FILE_STATUS_STARTED: String = "STARTED"
        const val LINKING_NETEX_FILE_STATUS_SUCCEEDED: String = "SUCCESS"
        const val LINKING_NETEX_FILE_STATUS_FAILED: String = "FAILED"
        const val LINKED_NETEX_FILE_PATH_HEADER: String = "LinkedNetexFilePath"
        const val LINKING_ERROR_CODE_HEADER: String = "LinkingFailureReason"
        // ISO-8601 instant stamped at emit time so marduk can order STARTED/SUCCESS by when
        // servicelinker actually emitted them, not by when marduk happened to process them.
        const val LINKING_STATUS_EVENT_TIME_HEADER: String = "LinkingStatusEventTime"
        const val SERVICE_LINK_MODES_HEADER: String = "ServiceLinkModes"
        const val CORRELATION_ID: String = "RutebankenCorrelationId"
        const val DATASET_REFERENTIAL: String = "EnturDatasetReferential"
        const val TARGET_FILE_HANDLE: String = "RutebankenTargetFileHandle"
    }
}

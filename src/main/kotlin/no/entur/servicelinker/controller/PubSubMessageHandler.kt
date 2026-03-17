package no.entur.servicelinker.controller

import com.google.cloud.spring.pubsub.core.PubSubTemplate
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage
import com.google.cloud.spring.pubsub.support.GcpPubSubHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

@Component
class PubSubMessageHandler(
    private val serviceLinkProducerService: ServiceLinkProducerService,
    private val pubSubTemplate: PubSubTemplate,
    @Value("\${servicelinker.pubsub.outbound.topic}") private val outboundTopic: String,
    @Value("\${gcs.bucket.input}") private val inputBucket: String
) {
    private val logger = LoggerFactory.getLogger(PubSubMessageHandler::class.java)

    @ServiceActivator(inputChannel = "servicelinkerInputChannel")
    fun handleMessage(message: Message<*>) {
        val acker = message.headers[GcpPubSubHeaders.ORIGINAL_MESSAGE] as? BasicAcknowledgeablePubsubMessage
        val attributes = acker?.pubsubMessage?.attributesMap ?: emptyMap()

        val correlationId = attributes[CORRELATION_ID] ?: "unknown"
        val datasetReferential = attributes[DATASET_REFERENTIAL] ?: "unknown"
        val modesFilter: Set<String>? = attributes[SERVICE_LINK_MODES_HEADER]?.let { value ->
            if (value.isBlank()) emptySet() else value.split(",").map { it.trim().uppercase() }.toSet()
        }

        MDC.put("correlationId", correlationId)
        MDC.put("codespace", datasetReferential)
        MDC.put("stage", "incoming")
        try {
            val targetFileHandle = attributes[TARGET_FILE_HANDLE]
                ?: throw IllegalArgumentException("Message missing 'RutebankenTargetFileHandle' attribute")

            logger.info("Received PubSub message: file={}, modesFilter={}", targetFileHandle, modesFilter)

            MDC.put("stage", "outgoing/started")
            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() + mapOf(LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_STARTED)
            )
            logger.info("Published STARTED status")

            MDC.put("stage", "processing")
            val addedCount = processWithRetry(inputBucket, targetFileHandle, modesFilter)

            MDC.put("stage", "outgoing/success")
            logger.info("Processing complete, added {} ServiceLinks. Publishing SUCCESS status.", addedCount)

            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() + mapOf(
                    LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_SUCCEEDED,
                    LINKED_NETEX_FILE_PATH_HEADER to targetFileHandle,
                    "addedServiceLinksCount" to addedCount.toString()
                )
            )
            logger.info("Published SUCCESS status")

            acker?.ack()
            logger.info("Message acknowledged")
        } catch (e: Exception) {
            MDC.put("stage", "outgoing/failed")
            logger.error("Failed to process PubSub message: {}", e.message, e)
            try {
                pubSubTemplate.publish(
                    outboundTopic,
                    "",
                    attributes.toMap() + mapOf(
                        LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_FAILED,
                        LINKING_ERROR_CODE_HEADER to (e.message ?: "Unknown error")
                    )
                )
                logger.info("Published FAILED status")
            } catch (publishEx: Exception) {
                logger.error("Failed to publish FAILED status: {}", publishEx.message)
            }
            acker?.ack()
            logger.info("Message acknowledged after failure")
        } finally {
            MDC.clear()
        }
    }

    private fun processWithRetry(bucketName: String, objectName: String, modesFilter: Set<String>?): Int {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                // MDCContext() captures the current MDC snapshot and restores it on each
                // coroutine resumption. MDC.put("stage",...) calls inside suspend functions
                // work as long as runBlocking executes on the calling thread (the default).
                // If a dispatcher change is introduced, stage updates may be lost after
                // suspension points — correlationId/codespace are unaffected.
                return runBlocking(MDCContext()) {
                    serviceLinkProducerService.processDataset(bucketName, objectName, modesFilter)
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn("Attempt ${attempt + 1}/$MAX_RETRIES failed for gs://$bucketName/$objectName: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastException ?: RuntimeException("Failed after $MAX_RETRIES attempts")
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L

        // Matches Constants.java in marduk
        const val LINKING_NETEX_FILE_STATUS_HEADER: String = "LinkingStatus"
        const val LINKING_NETEX_FILE_STATUS_STARTED: String = "STARTED"
        const val LINKING_NETEX_FILE_STATUS_SUCCEEDED: String = "SUCCESS"
        const val LINKING_NETEX_FILE_STATUS_FAILED: String = "FAILED"
        const val LINKED_NETEX_FILE_PATH_HEADER: String = "LinkedNetexFilePath"
        const val LINKING_ERROR_CODE_HEADER: String = "LinkingFailureReason"
        const val SERVICE_LINK_MODES_HEADER: String = "ServiceLinkModes"
        const val CORRELATION_ID: String = "RutebankenCorrelationId"
        const val DATASET_REFERENTIAL: String = "EnturDatasetReferential"
        const val TARGET_FILE_HANDLE: String = "RutebankenTargetFileHandle"

    }
}

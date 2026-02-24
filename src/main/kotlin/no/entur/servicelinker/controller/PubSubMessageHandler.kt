package no.entur.servicelinker.controller

import com.google.cloud.spring.pubsub.core.PubSubTemplate
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage
import com.google.cloud.spring.pubsub.support.GcpPubSubHeaders
import kotlinx.coroutines.runBlocking
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.slf4j.LoggerFactory
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

        logger.debug("Message headers: {}", message.headers.keys)
        logger.debug("Original message type: {}", message.headers[GcpPubSubHeaders.ORIGINAL_MESSAGE]?.javaClass?.name ?: "null")
        logger.debug("PubSub attributes: {}", attributes)

        val correlationId = attributes[CORRELATION_ID] ?: ""
        val datasetReferential = attributes[DATASET_REFERENTIAL] ?: ""
        val modesFilter: Set<String>? = attributes[SERVICE_LINK_MODES_HEADER]?.let { value ->
            if (value.isBlank()) emptySet() else value.split(",").map { it.trim().uppercase() }.toSet()
        }

        try {
            val targetFileHandle = attributes[TARGET_FILE_HANDLE]
                ?: throw IllegalArgumentException("Message missing 'RutebankenTargetFileHandle' attribute")

            logger.info("Received PubSub message: file=$targetFileHandle, correlationId=$correlationId, referential=$datasetReferential, modesFilter=$modesFilter")

            val addedCount = processWithRetry(inputBucket, targetFileHandle, modesFilter)

            logger.info("Processing complete. Added $addedCount ServiceLinks. Publishing status.")

            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() + mapOf(
                    LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_SUCCEEDED,
                    LINKED_NETEX_FILE_PATH_HEADER to targetFileHandle,
                    "addedServiceLinksCount" to addedCount.toString()
                )
            )

            acker?.ack()
            logger.info("Message acknowledged for correlationId=$correlationId")
        } catch (e: Exception) {
            logger.error("Failed to process PubSub message: ${e.message}", e)
            try {
                pubSubTemplate.publish(
                    outboundTopic,
                    "",
                    attributes.toMap() + mapOf(
                        LINKING_NETEX_FILE_STATUS_HEADER to LINKING_NETEX_FILE_STATUS_FAILED,
                        LINKING_ERROR_CODE_HEADER to (e.message ?: "Unknown error")
                    )
                )
            } catch (publishEx: Exception) {
                logger.error("Failed to publish FAILED status for correlationId=$correlationId: ${publishEx.message}")
            }
            acker?.ack()
        }
    }

    private fun processWithRetry(bucketName: String, objectName: String, modesFilter: Set<String>?): Int {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return runBlocking {
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

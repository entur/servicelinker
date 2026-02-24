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

        val correlationId = attributes["RutebankenCorrelationId"] ?: ""
        val datasetReferential = attributes["EnturDatasetReferential"] ?: ""
        val modesFilter: Set<String>? = attributes["ServiceLinkModes"]?.let { value ->
            if (value.isBlank()) emptySet() else value.split(",").map { it.trim().uppercase() }.toSet()
        }

        try {
            val targetFileHandle = attributes["RutebankenTargetFileHandle"]
                ?: throw IllegalArgumentException("Message missing 'RutebankenTargetFileHandle' attribute")

            logger.info("Received PubSub message: file=$targetFileHandle, correlationId=$correlationId, referential=$datasetReferential, modesFilter=$modesFilter")

            val addedCount = processWithRetry(inputBucket, targetFileHandle, modesFilter)

            logger.info("Processing complete. Added $addedCount ServiceLinks. Publishing status.")

            pubSubTemplate.publish(
                outboundTopic,
                "",
                attributes.toMap() + mapOf(
                    "LinkingStatus" to "SUCCESS",
                    "LinkedNetexFilePath" to targetFileHandle,
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
                        "LinkingStatus" to "FAILED",
                        "LinkingFailureReason" to (e.message ?: "Unknown error")
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

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }
}

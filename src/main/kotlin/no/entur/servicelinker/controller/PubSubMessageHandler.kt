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
    @Value("\${gcs.bucket.input}") private val inputBucket: String,
    @Value("\${gcs.bucket.output}") private val outputBucket: String
) {
    private val logger = LoggerFactory.getLogger(PubSubMessageHandler::class.java)

    @ServiceActivator(inputChannel = "servicelinkerInputChannel")
    fun handleMessage(message: Message<*>) {
        val acker = message.headers[GcpPubSubHeaders.ORIGINAL_MESSAGE] as? BasicAcknowledgeablePubsubMessage

        try {
            val attributes = acker?.pubsubMessage?.attributesMap ?: emptyMap()
            val objectName = attributes["objectName"]
                ?: throw IllegalArgumentException("Message missing 'objectName' attribute")
            val bucketName = attributes["bucketName"] ?: inputBucket
            val correlationId = attributes["correlationId"] ?: ""

            logger.info("Received PubSub message: bucket=$bucketName, object=$objectName, correlationId=$correlationId")

            val addedCount = processWithRetry(bucketName, objectName)

            logger.info("Processing complete. Added $addedCount ServiceLinks. Publishing to outbound topic.")

            pubSubTemplate.publish(
                outboundTopic,
                "processing complete",
                mapOf(
                    "objectName" to objectName,
                    "bucketName" to outputBucket,
                    "correlationId" to correlationId,
                    "addedServiceLinksCount" to addedCount.toString()
                )
            )

            acker?.ack()
            logger.info("Message acknowledged for correlationId=$correlationId")
        } catch (e: Exception) {
            logger.error("Failed to process PubSub message after $MAX_RETRIES attempts: ${e.message}")
            acker?.ack()
        }
    }

    private fun processWithRetry(bucketName: String, objectName: String): Int {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return runBlocking {
                    serviceLinkProducerService.processDataset(bucketName, objectName)
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

package no.entur.servicelinker.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.cloud.spring.pubsub.core.PubSubTemplate
import no.entur.servicelinker.model.ProcessingRequest
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class PubSubMessageHandler(
    private val serviceLinkProducerService: ServiceLinkProducerService,
    private val objectMapper: ObjectMapper,
    private val pubSubTemplate: PubSubTemplate
) {
    private val logger = LoggerFactory.getLogger(PubSubMessageHandler::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun startSubscription() {
        logger.info("Starting PubSub subscription for ServiceLink processing")
        // For now, we'll handle messages via REST API
        // PubSub integration can be added later when properly configured
    }

    suspend fun processMessageFromString(messagePayload: String) {
        try {
            logger.info("Processing message: $messagePayload")

            val request = objectMapper.readValue(messagePayload, ProcessingRequest::class.java)

            val result = serviceLinkProducerService.processNetexFile(request)

            if (result.success) {
                logger.info("Successfully processed file: ${result.outputFilePath}, added ${result.addedServiceLinksCount} ServiceLinks")
            } else {
                logger.error("Failed to process file: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            logger.error("Error processing message", e)
        }
    }
}

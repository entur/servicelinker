package no.entur.servicelinkproducer.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.cloud.spring.pubsub.core.PubSubTemplate
import mu.KotlinLogging
import no.entur.servicelinkproducer.model.ProcessingRequest
import no.entur.servicelinkproducer.service.ServiceLinkProducerService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class PubSubMessageHandler(
    private val serviceLinkProducerService: ServiceLinkProducerService,
    private val objectMapper: ObjectMapper,
    private val pubSubTemplate: PubSubTemplate
) {
    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun startSubscription() {
        logger.info { "Starting PubSub subscription for ServiceLink processing" }
        // For now, we'll handle messages via REST API
        // PubSub integration can be added later when properly configured
    }

    fun processMessageFromString(messagePayload: String) {
        try {
            logger.info { "Processing message: $messagePayload" }

            val request = objectMapper.readValue(messagePayload, ProcessingRequest::class.java)

            val result = serviceLinkProducerService.processNetexFile(request)

            if (result.success) {
                logger.info { "Successfully processed file: ${result.outputFilePath}, added ${result.addedServiceLinksCount} ServiceLinks" }
            } else {
                logger.error { "Failed to process file: ${result.errorMessage}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing message" }
        }
    }
}

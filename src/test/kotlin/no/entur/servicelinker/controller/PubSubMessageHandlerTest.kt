package no.entur.servicelinker.controller

import com.google.cloud.spring.pubsub.core.PubSubTemplate
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage
import com.google.cloud.spring.pubsub.support.GcpPubSubHeaders
import com.google.pubsub.v1.PubsubMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import no.entur.servicelinker.model.IncompleteJourneyPatternException
import no.entur.servicelinker.service.ServiceLinkProducerService
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PubSubMessageHandlerTest {

    // Wire-contract constants — must match marduk's Constants and PubSubMessageHandler's companion object.
    private val statusHeader = "LinkingStatus"
    private val eventTimeHeader = "LinkingStatusEventTime"

    private val service: ServiceLinkProducerService = mockk(relaxed = true)
    private val publishedAttributes = mutableListOf<Map<String, String>>()
    private val pubSubTemplate: PubSubTemplate =
        mockk {
            every { publish<String>(any(), any(), capture(publishedAttributes)) } returns CompletableFuture.completedFuture("msg-id")
        }
    private val handler = PubSubMessageHandler(service, pubSubTemplate, "ServicelinkerStatusQueue", "input-bucket")

    private fun inboundMessage(): Message<*> {
        val pubsubMessage =
            PubsubMessage
                .newBuilder()
                .putAllAttributes(
                    mapOf(
                        "RutebankenTargetFileHandle" to "tst/some-uuid/tst-netex.zip",
                        "RutebankenCorrelationId" to "corr-1",
                        "EnturDatasetReferential" to "rb_tst",
                    ),
                ).build()
        val acker = mockk<BasicAcknowledgeablePubsubMessage>(relaxed = true)
        every { acker.pubsubMessage } returns pubsubMessage
        return MessageBuilder.withPayload("").setHeader(GcpPubSubHeaders.ORIGINAL_MESSAGE, acker).build()
    }

    @Test
    fun `publishes parseable emit time on STARTED and SUCCESS`() {
        coEvery { service.processDataset(any(), any(), any()) } returns 7

        handler.handleMessage(inboundMessage())

        assertEquals(2, publishedAttributes.size, "Expected STARTED then SUCCESS to be published")
        assertEquals("STARTED", publishedAttributes[0][statusHeader])
        assertEquals("SUCCESS", publishedAttributes[1][statusHeader])
        assertParseableEmitTimes()
    }

    @Test
    fun `publishes parseable emit time on STARTED and FAILED`() {
        // IncompleteJourneyPatternException is rethrown without retry, so the FAILED path is exercised fast.
        coEvery { service.processDataset(any(), any(), any()) } throws IncompleteJourneyPatternException(emptyList(), "incomplete")

        handler.handleMessage(inboundMessage())

        assertEquals(2, publishedAttributes.size, "Expected STARTED then FAILED to be published")
        assertEquals("STARTED", publishedAttributes[0][statusHeader])
        assertEquals("FAILED", publishedAttributes[1][statusHeader])
        assertParseableEmitTimes()
    }

    private fun assertParseableEmitTimes() {
        publishedAttributes.forEach { attributes ->
            val emitTime = attributes[eventTimeHeader]
            assertNotNull(emitTime, "Every status message must carry $eventTimeHeader")
            Instant.parse(emitTime) // throws if not ISO-8601
        }
    }
}

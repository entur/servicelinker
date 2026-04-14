package no.entur.servicelinker.integration

import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.protobuf.ByteString
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.PushConfig
import com.google.pubsub.v1.TopicName
import io.grpc.ManagedChannelBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.gcloud.PubSubEmulatorContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

@Testcontainers
class PubSubIntegrationTest {
    companion object {
        private const val PROJECT_ID = "test-project"
        private const val INBOUND_TOPIC = "ServicelinkerInboundQueue"
        private const val OUTBOUND_TOPIC = "ServicelinkerStatusQueue"
        private const val INBOUND_SUB = "ServicelinkerInboundQueue"
        private const val OUTBOUND_SUB = "ServicelinkerStatusQueue"

        @Container
        @JvmStatic
        val pubsubEmulator: PubSubEmulatorContainer =
            PubSubEmulatorContainer(
                DockerImageName
                    .parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:latest")
                    .asCompatibleSubstituteFor("gcr.io/google.com/cloudsdktool/cloud-sdk"),
            )
    }

    private fun createChannelProvider(): FixedTransportChannelProvider {
        val channel = ManagedChannelBuilder.forTarget(pubsubEmulator.emulatorEndpoint).usePlaintext().build()
        return FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
    }

    @Test
    fun `should create topics subscriptions and publish messages through PubSub emulator`() {
        val channelProvider = createChannelProvider()
        val credentialsProvider = NoCredentialsProvider.create()

        // Create topics
        val topicAdminClient =
            TopicAdminClient.create(
                TopicAdminSettings
                    .newBuilder()
                    .setTransportChannelProvider(channelProvider)
                    .setCredentialsProvider(credentialsProvider)
                    .build(),
            )

        val inboundTopicName = TopicName.of(PROJECT_ID, INBOUND_TOPIC)
        val outboundTopicName = TopicName.of(PROJECT_ID, OUTBOUND_TOPIC)

        val createdInbound = topicAdminClient.createTopic(inboundTopicName)
        val createdOutbound = topicAdminClient.createTopic(outboundTopicName)

        assertEquals(inboundTopicName.toString(), createdInbound.name)
        assertEquals(outboundTopicName.toString(), createdOutbound.name)

        // Create subscriptions
        val subAdminClient =
            SubscriptionAdminClient.create(
                SubscriptionAdminSettings
                    .newBuilder()
                    .setTransportChannelProvider(channelProvider)
                    .setCredentialsProvider(credentialsProvider)
                    .build(),
            )

        val inboundSubName = ProjectSubscriptionName.of(PROJECT_ID, INBOUND_SUB)
        val outboundSubName = ProjectSubscriptionName.of(PROJECT_ID, OUTBOUND_SUB)

        subAdminClient.createSubscription(
            inboundSubName.toString(),
            inboundTopicName.toString(),
            PushConfig.getDefaultInstance(),
            10,
        )

        subAdminClient.createSubscription(
            outboundSubName.toString(),
            outboundTopicName.toString(),
            PushConfig.getDefaultInstance(),
            10,
        )

        // Publish a test message to inbound topic with expected attributes
        val publisher =
            Publisher
                .newBuilder(inboundTopicName)
                .setChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build()

        val message =
            PubsubMessage
                .newBuilder()
                .setData(ByteString.copyFromUtf8("test"))
                .putAttributes("RutebankenTargetFileHandle", "test-file.xml")
                .putAttributes("RutebankenCorrelationId", "test-correlation-123")
                .putAttributes("EnturDatasetReferential", "rb_test")
                .build()

        val messageId = publisher.publish(message).get(5, TimeUnit.SECONDS)
        assertTrue(messageId.isNotEmpty(), "Should receive a message ID")

        // Verify subscription exists by getting it
        val fetchedSub = subAdminClient.getSubscription(inboundSubName.toString())
        assertEquals(inboundTopicName.toString(), fetchedSub.topic)

        topicAdminClient.close()
        subAdminClient.close()
        publisher.shutdown()
    }
}

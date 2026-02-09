package no.entur.servicelinker.config

import com.google.cloud.spring.pubsub.core.PubSubTemplate
import com.google.cloud.spring.pubsub.integration.AckMode
import com.google.cloud.spring.pubsub.integration.inbound.PubSubInboundChannelAdapter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.channel.DirectChannel
import org.springframework.integration.config.EnableIntegration
import org.springframework.messaging.MessageChannel

@Configuration
@EnableIntegration
class PubSubConfig {

    @Bean
    fun servicelinkerInputChannel(): MessageChannel = DirectChannel()

    @Bean
    fun inboundChannelAdapter(
        servicelinkerInputChannel: MessageChannel,
        pubSubTemplate: PubSubTemplate,
        @Value("\${servicelinker.pubsub.inbound.subscription}") subscription: String
    ): PubSubInboundChannelAdapter {
        val adapter = PubSubInboundChannelAdapter(pubSubTemplate, subscription)
        adapter.setOutputChannel(servicelinkerInputChannel)
        adapter.setAckMode(AckMode.MANUAL)
        return adapter
    }
}

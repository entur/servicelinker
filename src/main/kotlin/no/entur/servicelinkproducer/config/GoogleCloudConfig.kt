package no.entur.servicelinkproducer.config

import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration

@Configuration
@EnableIntegration
class GoogleCloudConfig {

    @Bean
    fun storage(): Storage {
        return StorageOptions.getDefaultInstance().service
    }
}

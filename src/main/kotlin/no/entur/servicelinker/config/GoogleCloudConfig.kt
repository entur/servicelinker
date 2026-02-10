package no.entur.servicelinker.config

import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.core.NoCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.NoCredentials
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GoogleCloudConfig {

    private val logger = LoggerFactory.getLogger(GoogleCloudConfig::class.java)

    /**
     * Overrides Spring Cloud GCP's auto-configured CredentialsProvider (@ConditionalOnMissingBean).
     * Uses NoCredentials when PUBSUB_EMULATOR_HOST is set (local dev), real credentials otherwise.
     */
    @Bean
    fun googleCredentials(): CredentialsProvider {
        if (!System.getenv("STORAGE_EMULATOR_HOST").isNullOrBlank()) {
            logger.info("Emulator environment detected, using NoCredentialsProvider")
            return NoCredentialsProvider.create()
        }
        return CredentialsProvider { GoogleCredentials.getApplicationDefault() }
    }

    @Bean
    fun storage(
        @Value("\${spring.cloud.gcp.project-id:local-project}") projectId: String
    ): Storage {
        val emulatorHost = System.getenv("STORAGE_EMULATOR_HOST")
        if (!emulatorHost.isNullOrBlank()) {
            logger.info("Using GCS emulator at $emulatorHost")
            return StorageOptions.newBuilder()
                .setHost(emulatorHost)
                .setProjectId(projectId)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .service
        }
        return StorageOptions.newBuilder()
            .setProjectId(projectId)
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .build()
            .service
    }
}

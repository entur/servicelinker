package no.entur.servicelinkproducer.service

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class GcsService(
    private val storage: Storage,
    @Value("\${gcs.bucket.input}") private val inputBucket: String,
    @Value("\${gcs.bucket.output}") private val outputBucket: String
) {
    private val logger = KotlinLogging.logger {}

    fun downloadFile(gcsPath: String): InputStream? {
        return try {
            logger.info { "Downloading file from GCS: $gcsPath" }

            val parts = gcsPath.removePrefix("gs://").split("/", limit = 2)
            if (parts.size != 2) {
                logger.error { "Invalid GCS path format: $gcsPath" }
                return null
            }

            val bucket = parts[0]
            val objectName = parts[1]

            val blob = storage.get(BlobId.of(bucket, objectName))
            if (blob == null || !blob.exists()) {
                logger.error { "File not found: $gcsPath" }
                return null
            }

            ByteArrayInputStream(blob.getContent())
        } catch (e: Exception) {
            logger.error(e) { "Failed to download file from GCS: $gcsPath" }
            null
        }
    }

    fun uploadFile(content: ByteArray, originalGcsPath: String, targetBucket: String? = null): String? {
        return try {
            val bucket = targetBucket ?: outputBucket
            val originalFileName = extractFileName(originalGcsPath)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val outputFileName = "processed_${timestamp}_$originalFileName"

            logger.info { "Uploading processed file to GCS: gs://$bucket/$outputFileName" }

            val blobId = BlobId.of(bucket, outputFileName)
            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/xml")
                .build()

            storage.create(blobInfo, content)

            val outputPath = "gs://$bucket/$outputFileName"
            logger.info { "Successfully uploaded file to: $outputPath" }
            outputPath
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload file to GCS" }
            null
        }
    }

    fun uploadProcessedXml(content: ByteArray, originalFileName: String, targetBucket: String? = null): String? {
        return try {
            val bucket = targetBucket ?: outputBucket
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val outputFileName = "processed_${timestamp}_$originalFileName"

            logger.info { "Uploading processed XML to GCS: gs://$bucket/$outputFileName" }

            val blobId = BlobId.of(bucket, outputFileName)
            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/xml")
                .build()

            storage.create(blobInfo, content)

            val outputPath = "gs://$bucket/$outputFileName"
            logger.info { "Successfully uploaded XML to: $outputPath" }
            outputPath
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload XML to GCS" }
            null
        }
    }

    private fun extractFileName(gcsPath: String): String {
        return gcsPath.substringAfterLast("/")
    }

    fun fileExists(gcsPath: String): Boolean {
        return try {
            val parts = gcsPath.removePrefix("gs://").split("/", limit = 2)
            if (parts.size != 2) return false

            val bucket = parts[0]
            val objectName = parts[1]

            val blob = storage.get(BlobId.of(bucket, objectName))
            blob != null && blob.exists()
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if file exists: $gcsPath" }
            false
        }
    }
}

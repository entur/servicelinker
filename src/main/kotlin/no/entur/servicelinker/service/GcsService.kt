package no.entur.servicelinker.service

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class GcsService(
    private val storage: Storage
) {
    private val logger = LoggerFactory.getLogger(GcsService::class.java)

    fun downloadFile(bucket: String, objectName: String): ByteArray? {
        return try {
            logger.info("Downloading file from GCS: gs://$bucket/$objectName")
            val blob = storage.get(BlobId.of(bucket, objectName))
            if (blob == null || !blob.exists()) {
                logger.error("File not found: gs://$bucket/$objectName")
                return null
            }
            blob.getContent()
        } catch (e: Exception) {
            logger.error("Failed to download file from GCS: gs://$bucket/$objectName", e)
            null
        }
    }

    fun uploadFile(content: ByteArray, bucket: String, objectName: String, contentType: String = "application/xml"): String? {
        return try {
            logger.info("Uploading file to GCS: gs://$bucket/$objectName")
            val blobId = BlobId.of(bucket, objectName)
            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build()
            storage.create(blobInfo, content)
            val outputPath = "gs://$bucket/$objectName"
            logger.info("Successfully uploaded file to: $outputPath")
            outputPath
        } catch (e: Exception) {
            logger.error("Failed to upload file to GCS: gs://$bucket/$objectName", e)
            null
        }
    }
}

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

    fun downloadFile(bucket: String, objectName: String): ByteArray {
        logger.info("Downloading file from GCS: gs://$bucket/$objectName")
        val blob = storage.get(BlobId.of(bucket, objectName))
        if (blob == null || !blob.exists()) {
            throw RuntimeException("File not found: gs://$bucket/$objectName")
        }
        return blob.getContent()
    }

    fun uploadFile(content: ByteArray, bucket: String, objectName: String, contentType: String = "application/xml") {
        logger.info("Uploading file to GCS: gs://$bucket/$objectName")
        val blobId = BlobId.of(bucket, objectName)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(contentType)
            .build()
        storage.create(blobInfo, content)
        logger.info("Successfully uploaded file to: gs://$bucket/$objectName")
    }
}

package no.entur.servicelinker.service

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        val content = blob.getContent()
        logger.info("Downloaded {} bytes from gs://{}/{}", content.size, bucket, objectName)
        return content
    }

    fun uploadFile(content: ByteArray, bucket: String, objectName: String, contentType: String = "application/xml") {
        val sizeMb = content.size / (1024.0 * 1024.0)
        logger.info("Uploading {} MB to GCS: gs://{}/{}", String.format("%.2f", sizeMb), bucket, objectName)
        val blobId = BlobId.of(bucket, objectName)
        val blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(contentType)
            .build()

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable { storage.create(blobInfo, content) })
            future.get(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            logger.info("Successfully uploaded {} MB to gs://{}/{}", String.format("%.2f", sizeMb), bucket, objectName)
        } catch (e: TimeoutException) {
            throw RuntimeException(
                "GCS upload timed out after $UPLOAD_TIMEOUT_MINUTES minutes for gs://$bucket/$objectName (${String.format("%.2f", sizeMb)} MB)", e
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MINUTES = 10L
    }
}

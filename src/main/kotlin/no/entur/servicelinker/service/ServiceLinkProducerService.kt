package no.entur.servicelinker.service

import no.entur.servicelinker.model.DirectProcessingResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Service
class ServiceLinkProducerService(
    private val gcsService: GcsService,
    private val netexProcessor: NetexProcessor,
    private val osrmService: OSRMService,
    @Value("\${gcs.bucket.output}") private val outputBucket: String
) {
    private val logger = LoggerFactory.getLogger(ServiceLinkProducerService::class.java)

    suspend fun processDataset(inputBucket: String, objectName: String): Int {
        logger.info("Processing dataset: gs://$inputBucket/$objectName")

        val data = gcsService.downloadFile(inputBucket, objectName)
            ?: throw RuntimeException("Failed to download gs://$inputBucket/$objectName")

        val isZip = data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
                && data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

        val (processedData, totalAdded) = if (isZip || objectName.endsWith(".zip", ignoreCase = true)) {
            processZipArchive(data)
        } else {
            processSingleXml(data)
        }

        gcsService.uploadFile(
            processedData,
            outputBucket,
            objectName,
            if (isZip) "application/zip" else "application/xml"
        ) ?: throw RuntimeException("Failed to upload processed file to gs://$outputBucket/$objectName")

        logger.info("Dataset processing complete. Added $totalAdded ServiceLinks total. Output: gs://$outputBucket/$objectName")
        return totalAdded
    }

    private suspend fun processZipArchive(zipData: ByteArray): Pair<ByteArray, Int> {
        val outputBuffer = ByteArrayOutputStream()
        var totalAdded = 0

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            ZipOutputStream(outputBuffer).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryData = zis.readBytes()

                    if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                        logger.info("Processing XML entry: ${entry.name}")
                        val (processedXml, addedCount) = netexProcessor.processNetexFile(
                            ByteArrayInputStream(entryData)
                        )
                        totalAdded += addedCount

                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.write(processedXml)
                    } else {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.write(entryData)
                    }
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        return Pair(outputBuffer.toByteArray(), totalAdded)
    }

    private suspend fun processSingleXml(xmlData: ByteArray): Pair<ByteArray, Int> {
        return netexProcessor.processNetexFile(ByteArrayInputStream(xmlData))
    }

    suspend fun processXmlContentDirectly(xmlContent: String, filename: String): DirectProcessingResult {
        return try {
            logger.info("Starting direct ServiceLink production for XML content: $filename")
            val (processedXml, addedCount) = netexProcessor.processNetexFile(
                xmlContent.byteInputStream(Charsets.UTF_8)
            )
            logger.info("Successfully processed XML content. Added $addedCount ServiceLinks for file: $filename")
            DirectProcessingResult(
                success = true,
                processedXml = String(processedXml, Charsets.UTF_8),
                addedServiceLinksCount = addedCount
            )
        } catch (e: Exception) {
            logger.error("Error processing XML content directly: $filename", e)
            DirectProcessingResult(
                success = false,
                processedXml = null,
                addedServiceLinksCount = 0,
                errorMessage = "Processing error: ${e.message}"
            )
        }
    }

    fun getRoutingCacheStatistics(): Map<String, Any> {
        return mapOf(
            "cacheSize" to osrmService.getCacheSize(),
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun clearRoutingCache() {
        osrmService.clearCache()
        logger.info("Routing cache cleared")
    }
}

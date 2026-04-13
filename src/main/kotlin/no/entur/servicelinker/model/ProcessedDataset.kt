package no.entur.servicelinker.model

/** Output of dataset processing: the transformed NeTEx XML/ZIP bytes and how many ServiceLinks were added. */
data class ProcessedDataset(
    val outputBytes: ByteArray,
    val addedCount: Int,
)

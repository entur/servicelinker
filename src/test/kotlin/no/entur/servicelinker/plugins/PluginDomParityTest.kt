package no.entur.servicelinker.plugins

import io.mockk.mockk
import no.entur.servicelinker.service.NetexProcessor
import no.entur.servicelinker.service.OSRMService
import no.entur.servicelinker.service.StaxNetexTransformer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates that the netex-tools plugin-based Pass 1 extractor produces the same results
 * as the current DOM extractor in `NetexProcessor`.
 *
 * Fixtures:
 *  - sample-netex.xml                 — canonical ScheduledStopPoint + ServiceLink + JP shape.
 *  - sample-netex-stopplace.xml       — StopPlace variations (Centroid, quay-coords, bare Location,
 *                                       nested AlternativeName) to exercise the Centroid guard.
 *  - sample-netex-jpmode.xml          — every branch of buildJpModeMap, including the critical
 *                                       "LineRef to modeless Line + RouteRef fallback" case.
 *  - test-data/Havila_NeTEx_20260219.zip — real-world fixture, two XML entries.
 *
 * Divergence fails the test. This is the correctness gate for migrating Pass 1 call sites
 * to the plugin extractor.
 */
class PluginDomParityTest {

    private val dom: NetexProcessor = NetexProcessor(mockk<OSRMService>(relaxed = true), StaxNetexTransformer())

    @Test
    fun `sample-netex_xml — plugin extraction matches DOM extraction`() {
        assertParityForResource("sample-netex.xml")
    }

    @Test
    fun `sample-netex-stopplace_xml — StopPlace shapes match DOM extraction`() {
        assertParityForResource("sample-netex-stopplace.xml")
    }

    @Test
    fun `sample-netex-jpmode_xml — every buildJpModeMap branch matches DOM`() {
        assertParityForResource("sample-netex-jpmode.xml")
    }

    @Test
    fun `Havila zip entries — plugin extraction matches DOM extraction`() {
        val zipBytes = locateTestDataFile("Havila_NeTEx_20260219.zip").readBytes()
        val entries = unzip(zipBytes).filterKeys { it.endsWith(".xml", ignoreCase = true) }
        check(entries.isNotEmpty()) { "no XML entries in Havila zip" }

        // Run plugin extractor once over all documents (mirrors ZIP flow).
        val results = Pass1PluginExtractor().extract(entries)

        for ((name, xmlBytes) in entries) {
            val doc = parseDom(xmlBytes)
            val plugin = results.forDocument(name)
            assertParityForDoc(name, doc, plugin)
        }
    }

    @Test
    fun `Pass1PluginExtractor is safe to reuse — no state leaks between calls`() {
        val extractor = Pass1PluginExtractor()

        val firstBytes = readResource("sample-netex.xml")
        val secondBytes = readResource("sample-netex-stopplace.xml")

        val first = extractor.extract("first.xml", firstBytes)
        val second = extractor.extract("second.xml", secondBytes)

        // Second call's result must not include anything from the first fixture.
        val firstStopIds = first.stopIdsAndVersions.keys
        val secondStopIds = second.stopIdsAndVersions.keys
        assertTrue(firstStopIds.isNotEmpty(), "first call produced no stop ids")
        assertTrue(
            firstStopIds.none { it in secondStopIds },
            "state leaked between extract() calls: shared stop ids ${firstStopIds.intersect(secondStopIds)}"
        )

        // And re-extracting the first fixture returns the same data as before.
        val firstAgain = extractor.extract("first.xml", firstBytes)
        assertEquals(first, firstAgain, "repeated extract() produced different results")
    }

    private fun assertParityForResource(resource: String) {
        val bytes = readResource(resource)
        val doc = parseDom(bytes)
        val plugin = Pass1PluginExtractor().extract(resource, bytes)
        assertParityForDoc(resource, doc, plugin)
    }

    private fun assertParityForDoc(docName: String, doc: Document, plugin: DocResults) {
        assertEquals(
            dom.extractStopPoints(doc).sortedBy { it.id },
            plugin.stopPoints.sortedBy { it.id },
            "[$docName] stopPoints mismatch"
        )
        assertEquals(
            dom.extractScheduledStopPointIdsAndVersions(doc),
            plugin.stopIdsAndVersions,
            "[$docName] stopIdsAndVersions mismatch"
        )
        assertEquals(
            dom.extractStopAssignments(doc),
            plugin.assignments,
            "[$docName] stop→quay assignments mismatch"
        )
        assertEquals(
            dom.extractExistingServiceLinks(doc),
            plugin.existingServiceLinks,
            "[$docName] existing ServiceLinks mismatch"
        )
        assertEquals(
            dom.collectJpStopSequences(doc),
            plugin.stopSequences,
            "[$docName] JP stop sequences mismatch"
        )
        assertEquals(
            dom.collectExistingSlijpEntries(doc),
            plugin.existingSlijp,
            "[$docName] existing SLIJP entries mismatch"
        )
        assertEquals(
            dom.buildJpModeMap(doc),
            plugin.jpModeMap,
            "[$docName] jpModeMap mismatch"
        )
    }

    private fun readResource(name: String): ByteArray =
        javaClass.getResourceAsStream("/$name")?.readBytes()
            ?: error("test resource not found: $name")

    private fun parseDom(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun unzip(zip: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zis.readAllBytes()
            }
        }
        return out
    }

    /**
     * Resolve a file under `test-data/` robustly across Gradle and IDE runs. Walks upward
     * from the JVM's current working directory looking for a directory that contains a
     * `test-data/` folder.
     */
    private fun locateTestDataFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "test-data/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not locate test-data/$name starting from ${System.getProperty("user.dir")}")
    }
}

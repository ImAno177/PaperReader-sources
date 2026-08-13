package dev.paperreader.extensions.sources.europepmc

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuropePmcServiceTest {
    @Test
    fun `open access record keeps trusted full text and biomedical identifiers`() {
        val record = EuropePmcService.record(Json.parseToJsonElement(resource("europe-pmc-work.json")))

        requireNotNull(record)
        assertEquals("12345678", record.pmid)
        assertEquals("PMC1234567", record.pmcid)
        assertEquals("https://europepmc.org/articles/PMC1234567/bin/paper.pdf", record.manifestations.single().pdfUrl)
    }

    @Test
    fun `untrusted full text host is not exposed even when record says open access`() {
        val hostile = resource("europe-pmc-work.json").replace("https://europepmc.org/", "https://evil.example/")
        val record = EuropePmcService.record(Json.parseToJsonElement(hostile))
        assertNull(requireNotNull(record).manifestations.single().pdfUrl)
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/$name")).readText()
}

package dev.paperreader.extensions.sources.arxiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArxivServiceTest {
    @Test
    fun `atom parser preserves exact version metadata and safe links`() {
        val record = ArxivService.parse(resource("arxiv-response.xml")).single()

        assertEquals("2501.04510v2", record.arxivId)
        assertEquals("v2", record.manifestations.single().version)
        assertEquals("https://arxiv.org/pdf/2501.04510v2", record.manifestations.single().pdfUrl)
        assertEquals("2025-01-08", record.publishedDate)
    }

    @Test
    fun `doctype is rejected before XML parsing`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArxivService.parse("<!DOCTYPE feed [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><feed>&xxe;</feed>")
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/$name")).readText()
}

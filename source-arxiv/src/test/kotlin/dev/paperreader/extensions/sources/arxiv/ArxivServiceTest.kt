package dev.paperreader.extensions.sources.arxiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ArxivServiceTest {
    @Test
    fun `natural language search preserves the phrase as a title query`() {
        val query = ArxivService.buildSearchQuery(
            query = "attention is all you need",
            start = 0,
            limit = 5,
            sort = dev.paperreader.extensions.api.SourceSearchSort.RELEVANCE,
        )

        assertTrue(query.startsWith("search_query=ti%3A%22attention%20is%20all%20you%20need%22"))
        assertTrue(query.contains("sortBy=relevance&sortOrder=descending"))
    }

    @Test
    fun `natural language search escapes quote characters`() {
        val query = ArxivService.buildSearchQuery(
            query = "a \"quoted\" title",
            start = 10,
            limit = 2,
            sort = dev.paperreader.extensions.api.SourceSearchSort.NEWEST,
        )

        assertTrue(query.contains("%5C%22quoted%5C%22"))
        assertTrue(query.contains("start=10&max_results=2&sortBy=submittedDate&sortOrder=descending"))
    }

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

    @Test
    fun `abstract fallback preserves latest version for current paper`() {
        val record = requireNotNull(ArxivService.parseHtml(resource("arxiv-2501.04510.html"), "2501.04510"))

        assertEquals("2501.04510v2", record.arxivId)
        assertEquals("v2", record.manifestations.single().version)
        assertEquals("https://arxiv.org/abs/2501.04510v2", record.manifestations.single().landingPageUrl)
        assertEquals("https://arxiv.org/pdf/2501.04510v2", record.manifestations.single().pdfUrl)
        assertEquals("2025-01-08", record.publishedDate)
    }

    @Test
    fun `abstract fallback preserves latest version for older paper`() {
        val record = requireNotNull(ArxivService.parseHtml(resource("arxiv-1706.03762.html"), "1706.03762"))

        assertEquals("1706.03762v7", record.arxivId)
        assertEquals("v7", record.manifestations.single().version)
        assertEquals("2017-06-12", record.publishedDate)
    }

    @Test
    fun `abstract fallback keeps legacy category names containing v`() {
        val html = """
            <html><head><meta property="og:url" content="https://arxiv.org/abs/solv-int/9701001v2"></head>
            <body><h1 class="title"><span class="descriptor">Title:</span>Legacy paper</h1></body></html>
        """.trimIndent()

        val record = requireNotNull(ArxivService.parseHtml(html, "solv-int/9701001"))

        assertEquals("solv-int/9701001v2", record.arxivId)
    }

    @Test
    fun `abstract fallback rejects a different canonical id`() {
        val html = """
            <html><head><meta property="og:url" content="https://arxiv.org/abs/1706.03762v7"></head>
            <body><h1 class="title"><span class="descriptor">Title:</span>Other paper</h1></body></html>
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ArxivService.parseHtml(html, "2501.04510")
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/$name")).readText()
}

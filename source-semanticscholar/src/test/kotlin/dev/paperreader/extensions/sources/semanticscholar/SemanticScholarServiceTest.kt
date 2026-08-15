package dev.paperreader.extensions.sources.semanticscholar

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticScholarServiceTest {
    @Test
    fun `title shaped natural language uses closest title matching`() {
        assertEquals(true, SemanticScholarService.isTitleLikeQuery("attention is all you need"))
        assertEquals(false, SemanticScholarService.isTitleLikeQuery("graph neural networks"))
        assertEquals(false, SemanticScholarService.isTitleLikeQuery("https://arxiv.org/abs/1706.03762"))
    }

    @Test
    fun `record preserves canonical identifiers and citation observation`() {
        val record = SemanticScholarService.record(Json.parseToJsonElement(resource("semantic-scholar-paper.json")))

        requireNotNull(record)
        assertEquals("attention is all you need", record.title.lowercase())
        assertEquals("10.48550/arxiv.1706.03762", record.doi)
        assertEquals("1706.03762", record.arxivId)
        assertEquals(152_341, record.citationCount)
        assertEquals("2017-06-12", record.publishedDate)
    }

    @Test
    fun `malformed record without a trusted paper id is skipped`() {
        val record = SemanticScholarService.record(Json.parseToJsonElement("""{"paperId":"bad","title":"Paper"}"""))
        assertNull(record)
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/$name")).readText()
}

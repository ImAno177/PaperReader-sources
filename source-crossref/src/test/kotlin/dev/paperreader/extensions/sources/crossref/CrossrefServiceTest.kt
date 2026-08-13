package dev.paperreader.extensions.sources.crossref

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CrossrefServiceTest {
    @Test
    fun `exact DOI record normalizes JATS metadata and date`() {
        val root = Json.parseToJsonElement(resource("crossref-work.json")).jsonObject
        val record = CrossrefService.record(root, "10.1000/example")

        requireNotNull(record)
        assertEquals("A readable title", record.title)
        assertEquals("An abstract with markup.", record.abstractText)
        assertEquals(listOf("Ada Lovelace"), record.authors)
        assertEquals("2025-02-03", record.publishedDate)
    }

    @Test
    fun `mismatched DOI is rejected instead of contaminating identity`() {
        val root = Json.parseToJsonElement(resource("crossref-work.json")).jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            CrossrefService.record(root, "10.1000/different")
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/$name")).readText()
}

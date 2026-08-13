package dev.paperreader.extensions.sources.arxiv

import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourceManifestation
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.extensions.sources.common.PaperSourceService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

class ArxivService : PaperSourceService() {
    override val hostSignerSha256: String = BuildConfig.PAPERREADER_HOST_SIGNER_SHA256
    override val allowedHosts = setOf("export.arxiv.org")
    override val descriptor = SourceExtensionDescriptor(
        packageName = BuildConfig.APPLICATION_ID,
        providerId = "arxiv",
        displayName = "arXiv",
        minimumRequestIntervalMillis = 3_000,
        capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.PDF_LINK),
        roles = setOf(SourceRole.CONTENT_SOURCE),
        identifierLookupTypes = setOf(SourceIdentifierType.ARXIV),
        supportedSorts = SourceSearchSort.entries.toSet(),
    )

    override suspend fun searchSource(request: SourceSearchRequest): SourceSearchPage {
        val start = request.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val exactId = request.query.normalizeArxivId()
        val query = if (exactId != null) {
            "id_list=${encode(exactId)}&max_results=1"
        } else {
            val (sortBy, sortOrder) = when (request.sort) {
                SourceSearchSort.RELEVANCE -> "relevance" to "descending"
                SourceSearchSort.NEWEST -> "submittedDate" to "descending"
                SourceSearchSort.OLDEST -> "submittedDate" to "ascending"
            }
            "search_query=all:${encode(request.query)}&start=$start&max_results=${request.limit}" +
                "&sortBy=$sortBy&sortOrder=$sortOrder"
        }
        val records = parse(get(request.requestId, "$BASE/query?$query", "application/atom+xml"))
        return SourceSearchPage(
            requestId = request.requestId,
            records = records,
            nextCursor = (start + records.size).toString().takeIf {
                exactId == null && records.size >= request.limit
            },
        )
    }

    override suspend fun getPaperSource(request: SourceGetPaperRequest): SourcePaperResponse {
        val id = requireNotNull(request.providerRecordId.normalizeArxivId()) { "arXiv ID required" }
        val record = parse(
            get(request.requestId, "$BASE/query?id_list=${encode(id)}&max_results=1", "application/atom+xml"),
        ).firstOrNull()
        return SourcePaperResponse(request.requestId, record)
    }

    internal companion object {
    fun parse(xml: String): List<SourcePaperRecord> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isXIncludeAware = false }
            setExpandEntityReferences(false)
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(xml.byteInputStream())
        val entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry")
        return (0 until entries.length).mapNotNull { index ->
            val entry = entries.item(index) as Element
            val id = entry.text("id")?.normalizeArxivId() ?: return@mapNotNull null
            val title = entry.text("title")?.clean()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val publishedDate = entry.text("published")?.take(10)?.takeIf(ISO_DATE::matches)
            val links = entry.getElementsByTagNameNS(ATOM_NAMESPACE, "link")
            val linkElements = (0 until links.length).map { links.item(it) as Element }
            val landingPage = linkElements.firstOrNull { it.getAttribute("rel") == "alternate" }
                ?.getAttribute("href")
                ?.takeIf(String::isNotBlank)
                ?: "https://arxiv.org/abs/$id"
            val pdfUrl = linkElements.firstOrNull {
                it.getAttribute("title") == "pdf" || it.getAttribute("type") == "application/pdf"
            }?.getAttribute("href")?.takeIf(String::isNotBlank) ?: "https://arxiv.org/pdf/$id.pdf"
            val authors = entry.getElementsByTagNameNS(ATOM_NAMESPACE, "author").let { nodes ->
                (0 until nodes.length).mapNotNull { authorIndex ->
                    (nodes.item(authorIndex) as Element).text("name")?.clean()?.takeIf(String::isNotBlank)
                }
            }
            val subjects = entry.getElementsByTagNameNS(ATOM_NAMESPACE, "category").let { nodes ->
                (0 until nodes.length).mapNotNullTo(linkedSetOf()) { categoryIndex ->
                    (nodes.item(categoryIndex) as Element).getAttribute("term").trim().takeIf(String::isNotBlank)
                }
            }
            SourcePaperRecord(
                providerRecordId = id,
                title = title,
                abstractText = entry.text("summary")?.clean()?.takeIf(String::isNotBlank),
                authors = authors.take(100),
                subjects = subjects.take(100).toSet(),
                doi = entry.text("doi", ARXIV_NAMESPACE)?.normalizeDoi(),
                arxivId = id,
                publishedDate = publishedDate,
                updatedAt = entry.text("updated")?.takeIf(String::isNotBlank),
                manifestations = listOf(
                    SourceManifestation(
                        type = "preprint",
                        version = id.substringAfterLast('v', "").takeIf { it.all(Char::isDigit) }?.let { "v$it" },
                        landingPageUrl = landingPage,
                        pdfUrl = pdfUrl,
                        publishedDate = publishedDate,
                    ),
                ),
            )
        }
    }

    private fun Element.text(name: String, namespace: String = ATOM_NAMESPACE): String? =
        getElementsByTagNameNS(namespace, name).item(0)?.textContent?.trim()?.takeIf(String::isNotBlank)

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.normalizeArxivId(): String? {
        val value = trim()
            .substringAfter("arxiv.org/abs/", this)
            .removePrefix("arXiv:")
            .substringBefore('?')
        return value.takeIf(ARXIV_ID::matches)
    }

    private fun String.normalizeDoi(): String? = trim().lowercase().takeIf(DOI::matches)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        const val BASE = "https://export.arxiv.org/api"
        const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
        const val ARXIV_NAMESPACE = "http://arxiv.org/schemas/atom"
        val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        val DOI = Regex("10\\.\\d{4,9}/\\S+", RegexOption.IGNORE_CASE)
        val ARXIV_ID = Regex(
            "(?:\\d{4}\\.\\d{4,5}|[a-z][a-z0-9.-]*/\\d{7})(?:v\\d+)?",
            RegexOption.IGNORE_CASE,
        )
    }
}

package dev.paperreader.extensions.sources.semanticscholar

import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.extensions.sources.common.PaperSourceService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SemanticScholarService : PaperSourceService() {
    override val hostSignerSha256: String = BuildConfig.PAPERREADER_HOST_SIGNER_SHA256
    override val allowedHosts = setOf("api.semanticscholar.org")
    override val descriptor = SourceExtensionDescriptor(
        packageName = BuildConfig.APPLICATION_ID,
        providerId = "semanticscholar",
        displayName = "Semantic Scholar",
        minimumRequestIntervalMillis = 1_000,
        capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS),
        roles = setOf(SourceRole.SEARCH_ENGINE),
        identifierLookupTypes = emptySet(),
        supportedSorts = setOf(SourceSearchSort.RELEVANCE),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchSource(request: SourceSearchRequest): SourceSearchPage {
        require(request.sort == SourceSearchSort.RELEVANCE) { "Semantic Scholar supports relevance sort only" }
        val offset = request.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val fields = "paperId,title,abstract,authors,year,publicationDate,externalIds,citationCount"
        val root = json.parseToJsonElement(
            get(
                request.requestId,
                "$BASE/paper/search?query=${encode(request.query)}&offset=$offset&limit=${request.limit}&fields=$fields",
            ),
        ).jsonObject
        val records = root["data"]?.jsonArray.orEmpty().mapNotNull(::record).take(request.limit)
        val nextCursor = root["next"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()
            ?.takeIf { it > offset }
            ?.toString()
        return SourceSearchPage(request.requestId, records, nextCursor)
    }

    override suspend fun getPaperSource(request: SourceGetPaperRequest): SourcePaperResponse {
        require(PAPER_ID.matches(request.providerRecordId)) { "Semantic Scholar paper ID required" }
        val fields = "paperId,title,abstract,authors,year,publicationDate,externalIds,citationCount"
        val result = try {
            record(json.parseToJsonElement(get(request.requestId, "$BASE/paper/${request.providerRecordId}?fields=$fields")))
        } catch (_: SourceNotFoundException) {
            null
        }
        return SourcePaperResponse(request.requestId, result)
    }

    internal companion object {
    fun record(element: JsonElement): SourcePaperRecord? {
        val value = element.jsonObject
        val id = value["paperId"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(PAPER_ID::matches)
            ?: return null
        val title = value["title"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val externalIds = value["externalIds"]?.jsonObject
        val doi = externalIds?.get("DOI")?.jsonPrimitive?.contentOrNull?.normalizeDoi()
        val arxivId = externalIds?.get("ArXiv")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val pmid = externalIds?.get("PubMed")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[1-9]\\d{0,9}")) }
        val pmcid = externalIds?.get("PubMedCentral")?.jsonPrimitive?.contentOrNull
            ?.uppercase()
            ?.takeIf { it.matches(Regex("PMC[1-9]\\d{0,9}")) }
        val publishedDate = value["publicationDate"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(ISO_DATE::matches)
            ?: value["year"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it in 1..9999 }
                ?.let { "%04d-01-01".format(it) }
        val authors = value["authors"]?.jsonArray.orEmpty().mapNotNull { author ->
            author.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }
        return SourcePaperRecord(
            providerRecordId = id,
            title = title,
            abstractText = value["abstract"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            authors = authors.take(100),
            doi = doi,
            arxivId = arxivId,
            pmid = pmid,
            pmcid = pmcid,
            citationCount = value["citationCount"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 },
            publishedDate = publishedDate,
        )
    }

    private fun String.normalizeDoi(): String? = trim()
        .removePrefix("https://doi.org/")
        .lowercase()
        .takeIf { it.matches(Regex("10\\.\\d{4,9}/\\S+")) }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")

        const val BASE = "https://api.semanticscholar.org/graph/v1"
        val PAPER_ID = Regex("[0-9a-fA-F]{40}")
        val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

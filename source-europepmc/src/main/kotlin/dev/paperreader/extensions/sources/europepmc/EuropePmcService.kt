package dev.paperreader.extensions.sources.europepmc

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EuropePmcService : PaperSourceService() {
    override val hostSignerSha256: String = BuildConfig.PAPERREADER_HOST_SIGNER_SHA256
    override val allowedHosts = setOf("www.ebi.ac.uk")
    override val descriptor = SourceExtensionDescriptor(
        packageName = BuildConfig.APPLICATION_ID,
        providerId = "europepmc",
        displayName = "Europe PMC",
        minimumRequestIntervalMillis = 1_000,
        capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.PDF_LINK),
        roles = setOf(SourceRole.CONTENT_SOURCE),
        identifierLookupTypes = setOf(
            SourceIdentifierType.DOI,
            SourceIdentifierType.PMID,
            SourceIdentifierType.PMCID,
        ),
        supportedSorts = setOf(SourceSearchSort.RELEVANCE, SourceSearchSort.NEWEST),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchSource(request: SourceSearchRequest): SourceSearchPage {
        require(request.sort != SourceSearchSort.OLDEST) { "Europe PMC does not support oldest sort" }
        val exactQuery = request.query.exactQuery()
        val baseQuery = exactQuery ?: request.query
        val query = if (request.sort == SourceSearchSort.NEWEST && exactQuery == null) {
            "($baseQuery) sort_date:y"
        } else {
            baseQuery
        }
        val pageSize = if (exactQuery == null) request.limit else 1
        val cursor = request.cursor
            ?.takeIf { exactQuery == null }
            ?.let { "&cursorMark=${encode(it)}" }
            .orEmpty()
        val root = json.parseToJsonElement(
            get(
                request.requestId,
                "$BASE/search?query=${encode(query)}&format=json&resultType=core&pageSize=$pageSize$cursor",
            ),
        ).jsonObject
        val records = root["resultList"]?.jsonObject?.get("result")?.jsonArray.orEmpty().mapNotNull(::record)
        val nextCursor = root["nextCursorMark"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { exactQuery == null && it.isNotBlank() && it != request.cursor }
        return SourceSearchPage(request.requestId, records, nextCursor)
    }

    override suspend fun getPaperSource(request: SourceGetPaperRequest): SourcePaperResponse {
        require(PROVIDER_RECORD_ID.matches(request.providerRecordId)) { "Europe PMC record ID required" }
        val source = request.providerRecordId.substringBefore(':').uppercase()
        val id = request.providerRecordId.substringAfter(':')
        val page = searchSource(
            SourceSearchRequest(
                requestId = request.requestId,
                query = "EXT_ID:$id AND SRC:$source",
                limit = 1,
            ),
        )
        return SourcePaperResponse(request.requestId, page.records.firstOrNull())
    }

    internal companion object {
    fun record(element: JsonElement): SourcePaperRecord? {
        val value = element.jsonObject
        val source = value["source"]?.jsonPrimitive?.contentOrNull
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z0-9_-]{2,10}")) }
            ?: return null
        val id = value["id"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) }
            ?: return null
        val title = value["title"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val doi = value["doi"]?.jsonPrimitive?.contentOrNull?.normalizeDoi()
        val pmid = value["pmid"]?.jsonPrimitive?.contentOrNull?.takeIf(PMID::matches)
        val pmcid = value["pmcid"]?.jsonPrimitive?.contentOrNull
            ?.uppercase()
            ?.takeIf(PMCID::matches)
        val publishedDate = (value["firstPublicationDate"] ?: value["electronicPublicationDate"])
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(ISO_DATE::matches)
        val authors = value["authorList"]?.jsonObject?.get("author")?.jsonArray.orEmpty().mapNotNull { author ->
            val data = author.jsonObject
            (data["fullName"] ?: data["lastName"])
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        val isOpenAccess = value["isOpenAccess"]?.jsonPrimitive?.contentOrNull.equals("Y", ignoreCase = true)
        val pdfUrl = value["fullTextUrlList"]?.jsonObject?.get("fullTextUrl")?.jsonArray.orEmpty()
            .firstOrNull { link ->
                link.jsonObject["documentStyle"]?.jsonPrimitive?.contentOrNull.equals("pdf", ignoreCase = true)
            }
            ?.jsonObject
            ?.get("url")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { isOpenAccess && it.isTrustedEuropePmcFullTextUrl() }
        return SourcePaperRecord(
            providerRecordId = "${source.lowercase()}:${id.lowercase()}",
            title = title,
            abstractText = value["abstractText"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            authors = authors.take(100),
            doi = doi,
            pmid = pmid,
            pmcid = pmcid,
            publishedDate = publishedDate,
            manifestations = listOf(
                SourceManifestation(
                    type = "version_of_record",
                    landingPageUrl = "https://europepmc.org/article/$source/$id",
                    pdfUrl = pdfUrl,
                    publishedDate = publishedDate,
                ),
            ),
        )
    }

    private fun String.exactQuery(): String? {
        val value = trim()
        return when {
            value.matches(Regex("(?i)^pmcid:\\s*PMC\\d+$")) ->
                "PMCID:${value.substringAfter(':').trim().uppercase()}"
            value.matches(Regex("(?i)^(?:pmid:\\s*)?\\d+$")) ->
                "EXT_ID:${value.substringAfter(':').trim()} AND SRC:MED"
            value.normalizeDoi() != null -> "DOI:${value.normalizeDoi()}"
            else -> null
        }
    }

    private fun String.normalizeDoi(): String? = trim()
        .removePrefix("https://doi.org/")
        .lowercase()
        .takeIf(DOI::matches)

    private fun String.isTrustedEuropePmcFullTextUrl(): Boolean = runCatching {
        val uri = java.net.URI(this)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.host?.lowercase() in setOf("europepmc.org", "www.ebi.ac.uk")
    }.getOrDefault(false)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        const val BASE = "https://www.ebi.ac.uk/europepmc/webservices/rest"
        val PROVIDER_RECORD_ID = Regex("[a-z0-9_-]{2,10}:[A-Za-z0-9._-]{1,100}")
        val DOI = Regex("10\\.\\d{4,9}/\\S+", RegexOption.IGNORE_CASE)
        val PMID = Regex("[1-9]\\d{0,9}")
        val PMCID = Regex("PMC[1-9]\\d{0,9}")
        val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

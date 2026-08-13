package dev.paperreader.extensions.sources.crossref

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

class CrossrefService : PaperSourceService() {
    override val hostSignerSha256: String = BuildConfig.PAPERREADER_HOST_SIGNER_SHA256
    override val allowedHosts = setOf("api.crossref.org")
    override val descriptor = SourceExtensionDescriptor(
        packageName = BuildConfig.APPLICATION_ID,
        providerId = "crossref",
        displayName = "Crossref",
        minimumRequestIntervalMillis = 1_000,
        capabilities = setOf(SourceCapability.DETAILS),
        roles = setOf(SourceRole.METADATA_ENGINE),
        identifierLookupTypes = setOf(SourceIdentifierType.DOI),
        supportedSorts = setOf(SourceSearchSort.RELEVANCE),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchSource(request: SourceSearchRequest): SourceSearchPage {
        val doi = request.query.normalizeDoi() ?: return SourceSearchPage(request.requestId, emptyList())
        return SourceSearchPage(request.requestId, listOfNotNull(fetch(request.requestId, doi)))
    }

    override suspend fun getPaperSource(request: SourceGetPaperRequest): SourcePaperResponse {
        val doi = requireNotNull(request.providerRecordId.normalizeDoi()) { "DOI required" }
        return SourcePaperResponse(request.requestId, fetch(request.requestId, doi))
    }

    private suspend fun fetch(requestId: String, requestedDoi: String): SourcePaperRecord? {
        val root = try {
            json.parseToJsonElement(get(requestId, "$BASE/works/${encode(requestedDoi)}"))
                .jsonObject["message"]
                ?.jsonObject
                ?: return null
        } catch (_: SourceNotFoundException) {
            return null
        }
        return record(root, requestedDoi)
    }

    internal companion object {
    fun record(root: JsonObject, requestedDoi: String): SourcePaperRecord? {
        val doi = root["DOI"]?.jsonPrimitive?.contentOrNull?.normalizeDoi() ?: return null
        require(doi == requestedDoi) { "Crossref returned a different DOI" }
        val title = root["title"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?.let(::plainText)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val authors = root["author"]?.jsonArray.orEmpty().mapNotNull { element ->
            val author = element.jsonObject
            val person = listOfNotNull(
                author["given"]?.jsonPrimitive?.contentOrNull,
                author["family"]?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ").trim()
            person.takeIf(String::isNotBlank)
                ?: author["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }
        val subjects = root["subject"]?.jsonArray.orEmpty().mapNotNullTo(linkedSetOf()) {
            it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }
        val publishedDate = root["published-print"]?.jsonObject?.date()
            ?: root["published-online"]?.jsonObject?.date()
            ?: root["issued"]?.jsonObject?.date()
        return SourcePaperRecord(
            providerRecordId = doi,
            title = title,
            abstractText = root["abstract"]?.jsonPrimitive?.contentOrNull
                ?.let(::plainText)
                ?.takeIf(String::isNotBlank),
            authors = authors.take(100),
            subjects = subjects.take(100).toSet(),
            doi = doi,
            publishedDate = publishedDate,
        )
    }

    private fun JsonObject.date(): String? {
        val parts = this["date-parts"]?.jsonArray?.firstOrNull()?.jsonArray ?: return null
        val year = parts.getOrNull(0)?.jsonPrimitive?.intOrNull?.takeIf { it in 1..9999 } ?: return null
        val month = parts.getOrNull(1)?.jsonPrimitive?.intOrNull?.takeIf { it in 1..12 } ?: 1
        val day = parts.getOrNull(2)?.jsonPrimitive?.intOrNull?.takeIf { it in 1..31 } ?: 1
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun plainText(value: String): String = Jsoup.parse(value).text().trim()

    private fun String.normalizeDoi(): String? = trim()
        .removePrefix("https://doi.org/")
        .removePrefix("http://doi.org/")
        .removePrefix("doi:")
        .trim()
        .lowercase()
        .takeIf { it.matches(Regex("10\\.\\d{4,9}/\\S+")) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        const val BASE = "https://api.crossref.org"
    }
}

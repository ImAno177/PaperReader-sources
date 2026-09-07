package dev.paperreader.extensions.sources.arxiv

import dev.paperreader.extensions.api.SourceReadableAsset
import dev.paperreader.extensions.api.SourceReadableSection
import dev.paperreader.extensions.api.SourceReadableWarning
import java.net.URI
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

internal const val ARXIV_READABLE_CONTRACT_VERSION = "arxiv-html-sanitizer-1"

internal data class SanitizedArxivReadableDocument(
    val bodyHtml: String,
    val title: String,
    val sourceLicense: String?,
    val sections: List<SourceReadableSection>,
    val warnings: Set<SourceReadableWarning>,
    val assets: List<SourceReadableAsset>,
)

/**
 * Provider-owned HTML normalization. The host receives only this sanitized fragment and opaque
 * asset references; it never parses arbitrary provider HTML in its UI process.
 */
internal class ArxivReadableDocumentSanitizer {
    fun sanitize(rawHtml: String, sourceUrl: String): SanitizedArxivReadableDocument? {
        val sourceUri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (sourceUri.scheme != "https" || sourceUri.host != "arxiv.org" || sourceUri.fragment != null) return null
        val parsed = Jsoup.parse(rawHtml, sourceUrl)
        val article = parsed.selectFirst("article.ltx_document")?.clone() ?: return null
        if (article.text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return null
        normalizeAuthorLayout(article)
        normalizeTableLayout(article)
        val warnings = linkedSetOf<SourceReadableWarning>()
        if (normalizeKnownConversionArtifacts(article)) {
            warnings += SourceReadableWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED
        }
        replaceUnsupportedEmbeddedFigures(article, warnings)
        annotateReadableBlocks(article)
        val sections = extractSections(parsed)
        if (sections.isEmpty()) warnings += SourceReadableWarning.TABLE_OF_CONTENTS_MISSING
        val container = Element("div").addClass("paperreader-document").appendChild(article)
        val cleaned = Jsoup.clean(
            container.outerHtml(),
            sourceUrl,
            readableSafelist(),
            Document.OutputSettings().prettyPrint(false),
        )
        val cleanedDocument = Jsoup.parseBodyFragment(cleaned, sourceUrl)
        normalizeLinks(cleanedDocument, sourceUri)
        val assets = referenceFigures(cleanedDocument.body(), sourceUri, warnings)
        val bodyHtml = cleanedDocument.body().html().trim()
        if (cleanedDocument.body().text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return null
        if (containsExecutableMarkup(cleanedDocument)) return null
        val title = article.selectFirst("h1.ltx_title_document")?.text()?.clean()
            ?.takeIf(String::isNotBlank)
            ?: parsed.selectFirst("title")?.text()?.clean()?.takeIf(String::isNotBlank)
            ?: return null
        val sourceLicense = parsed.selectFirst("#license-tr")?.text()?.clean()
            ?.take(MAXIMUM_LICENSE_LENGTH)?.takeIf(String::isNotBlank)
        return SanitizedArxivReadableDocument(
            bodyHtml = bodyHtml,
            title = title,
            sourceLicense = sourceLicense,
            sections = sections,
            warnings = warnings,
            assets = assets,
        )
    }

    private fun annotateReadableBlocks(article: Element) {
        article.select("[data-paperreader-block-id]").removeAttr("data-paperreader-block-id")
        article.select(READABLE_BLOCK_SELECTOR).forEachIndexed { index, element ->
            element.attr("data-paperreader-block-id", "prx-b${index.toString().padStart(5, '0')}")
        }
    }

    private fun normalizeAuthorLayout(article: Element) {
        val authors = article.selectFirst(".ltx_authors") ?: return
        authors.select(".ltx_author_before").remove()
        authors.select(".ltx_creator").forEach { creator ->
            creator.addClass("paperreader-author")
            creator.selectFirst(".ltx_personname")?.addClass("paperreader-author-name")
            creator.select(".ltx_author_notes").forEach { it.addClass("paperreader-author-details") }
        }
    }

    private fun normalizeTableLayout(article: Element) {
        article.select("table").toList().forEach { table ->
            if (table.parent()?.hasClass("paperreader-table-scroll") == true) return@forEach
            val wrapper = Element("div").addClass("paperreader-table-scroll")
            table.replaceWith(wrapper)
            wrapper.appendChild(table)
        }
    }

    private fun replaceUnsupportedEmbeddedFigures(
        article: Element,
        warnings: MutableSet<SourceReadableWarning>,
    ) {
        article.select("object").toList().forEach { objectElement ->
            val type = objectElement.attr("type").substringBefore(';').trim().lowercase()
            if (type != "image/svg+xml" || objectElement.attr("data").isBlank()) {
                replaceUnavailableFigure(objectElement)
                warnings += SourceReadableWarning.FIGURE_UNAVAILABLE
            }
        }
    }

    private fun extractSections(document: Document): List<SourceReadableSection> = document
        .select("nav.ltx_TOC li > a[href^=#]")
        .asSequence()
        .mapNotNull { link ->
            val anchor = link.attr("href").removePrefix("#")
            val title = link.text().clean().take(MAXIMUM_SECTION_TITLE_LENGTH)
            if (!anchor.matches(SAFE_ANCHOR) || title.isBlank()) return@mapNotNull null
            val parent = link.parent()
            val level = when {
                parent?.hasClass("ltx_tocentry_subsubsection") == true -> 3
                parent?.hasClass("ltx_tocentry_subsection") == true -> 2
                else -> 1
            }
            SourceReadableSection(anchor, title, level)
        }
        .distinctBy(SourceReadableSection::anchor)
        .take(MAXIMUM_SECTION_COUNT)
        .toList()

    private fun referenceFigures(
        container: Element,
        sourceUri: URI,
        warnings: MutableSet<SourceReadableWarning>,
    ): List<SourceReadableAsset> {
        val references = linkedMapOf<String, SourceReadableAsset>()
        container.select("img[src], object[data]").toList().forEach { element ->
            val rawUrl = if (element.tagName().equals("object", ignoreCase = true)) {
                element.attr("data")
            } else {
                element.attr("src")
            }
            val url = resolveFigureUrl(rawUrl, sourceUri)
            if (url == null) {
                replaceUnavailableFigure(element)
                warnings += SourceReadableWarning.FIGURE_UNAVAILABLE
                return@forEach
            }
            val id = sha256(url.toByteArray(Charsets.UTF_8))
            val asset = SourceReadableAsset(id, url, guessMediaType(url))
            references.putIfAbsent(id, asset)
            if (element.tagName().equals("object", ignoreCase = true)) {
                Element("img").apply {
                    attr("src", "paperreader-asset://$id")
                    attr("alt", figureAlt(element))
                    attr("loading", "lazy")
                    attr("decoding", "async")
                }.also(element::replaceWith)
            } else {
                element.attr("src", "paperreader-asset://$id")
                element.attr("loading", "lazy")
                element.attr("decoding", "async")
                element.removeAttr("srcset")
                if (element.attr("alt").isBlank()) element.attr("alt", figureAlt(element))
            }
        }
        return references.values.toList()
    }

    private fun normalizeLinks(document: Document, sourceUri: URI) {
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val uri = runCatching { URI(href) }.getOrNull()
            when {
                href.startsWith("#") -> Unit
                uri == null -> link.removeAttr("href")
                uri.scheme == "https" && uri.host == sourceUri.host && uri.path == sourceUri.path &&
                    !uri.fragment.isNullOrBlank() -> link.attr("href", "#${uri.fragment}")
                uri.scheme !in setOf("https", "mailto") || uri.userInfo != null -> link.removeAttr("href")
                else -> link.attr("rel", "external nofollow noopener noreferrer")
            }
        }
    }

    private fun resolveFigureUrl(rawUrl: String, sourceUri: URI): String? {
        val resolved = runCatching { sourceUri.resolve(rawUrl) }.getOrNull() ?: return null
        return resolved.toString().takeIf {
            resolved.scheme == "https" && resolved.host == "arxiv.org" && resolved.fragment == null &&
                resolved.userInfo == null && resolved.port == -1 && resolved.query == null &&
                resolved.path.startsWith(sourceUri.path.trimEnd('/') + "/") && it.length <= 2_048
        }
    }

    private fun containsExecutableMarkup(document: Document): Boolean =
        document.select("script, style, link, base, iframe, frame, object, embed, form, input, button, textarea, select, svg").isNotEmpty() ||
            document.allElements.any { element ->
                element.attributes().any { attribute -> attribute.key.startsWith("on", ignoreCase = true) }
            }

    private fun readableSafelist(): Safelist = Safelist.none()
        .addTags(*SAFE_HTML_TAGS)
        .addAttributes(
            ":all",
            "id", "class", "title", "lang", "dir", "role",
            "aria-label", "aria-labelledby", "aria-describedby", "aria-hidden",
            "data-paperreader-block-id",
        )
        .addAttributes("a", "href")
        .addProtocols("a", "href", "https", "mailto", "#")
        .addAttributes("img", "src", "alt", "width", "height", "loading", "decoding")
        .addProtocols("img", "src", "https")
        .addTags("object")
        .addAttributes("object", "data", "type", "width", "height", "alt")
        .addProtocols("object", "data", "https")
        .addAttributes("ol", "start", "reversed")
        .addAttributes("li", "value")
        .addAttributes("th", "colspan", "rowspan", "scope", "headers", "abbr")
        .addAttributes("td", "colspan", "rowspan", "headers")
        .also { safelist ->
            SAFE_MATHML_TAGS.forEach { tag -> safelist.addTags(tag) }
            SAFE_MATHML_TAGS.forEach { tag -> safelist.addAttributes(tag, *SAFE_MATHML_ATTRIBUTES) }
        }

    private fun replaceUnavailableFigure(element: Element) {
        val caption = element.closest("figure")?.selectFirst("figcaption")?.text()?.clean()
        element.replaceWith(
            Element("p").addClass("paperreader-figure-unavailable").text(
                caption?.let { "Figure unavailable: $it" } ?: "Figure unavailable",
            ),
        )
    }

    private fun figureAlt(element: Element): String =
        element.attr("alt").takeIf(String::isNotBlank)
            ?: element.closest("figure")?.selectFirst("figcaption")?.text()?.clean()
                ?.takeIf(String::isNotBlank)
            ?: "Figure image"

    private fun normalizeKnownConversionArtifacts(article: Element): Boolean {
        var normalized = false
        article.getAllElements().forEach { element ->
            element.textNodes().forEach { node ->
                val source = node.text()
                val replacement = source.replace(CIRCLED_STEP_ARTIFACT) { match ->
                    normalized = true
                    "${match.groupValues[1]}↝"
                }
                if (replacement != source) node.text(replacement)
            }
        }
        return normalized
    }

    private fun guessMediaType(url: String): String = when {
        url.substringBefore('?').lowercase().endsWith(".svg") -> "image/svg+xml"
        url.substringBefore('?').lowercase().endsWith(".jpg") ||
            url.substringBefore('?').lowercase().endsWith(".jpeg") -> "image/jpeg"
        url.substringBefore('?').lowercase().endsWith(".webp") -> "image/webp"
        url.substringBefore('?').lowercase().endsWith(".gif") -> "image/gif"
        else -> "image/png"
    }

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MINIMUM_ARTICLE_TEXT_LENGTH = 300
        const val MAXIMUM_LICENSE_LENGTH = 160
        const val MAXIMUM_SECTION_TITLE_LENGTH = 180
        const val MAXIMUM_SECTION_COUNT = 120
        val SAFE_ANCHOR = Regex("[A-Za-z0-9._:-]{1,160}")
        val CIRCLED_STEP_ARTIFACT = Regex("""\\raisebox\{[-+]?(?:\d+(?:\.\d+)?|\.\d+)pt\}\{\\scriptsize\s*([0-9]{1,2})\}⃝""")
        const val READABLE_BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,dt,dd,figcaption,pre,blockquote,th,td"
        val SAFE_HTML_TAGS = arrayOf(
            "article", "section", "nav", "header", "footer", "div", "span",
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "ol", "ul", "li",
            "dl", "dt", "dd", "figure", "figcaption", "img", "table", "caption",
            "thead", "tbody", "tfoot", "tr", "th", "td", "colgroup", "col",
            "blockquote", "pre", "code", "kbd", "samp", "var", "strong", "b",
            "em", "i", "u", "s", "small", "sup", "sub", "br", "hr", "details",
            "summary", "time", "address", "abbr", "cite", "q", "mark",
        )
        val SAFE_MATHML_TAGS = arrayOf(
            "math", "mrow", "mi", "mn", "mo", "ms", "mtext", "mspace", "mfrac",
            "msqrt", "mroot", "mstyle", "merror", "mpadded", "mphantom", "mfenced",
            "menclose", "msub", "msup", "msubsup", "munder", "mover", "munderover",
            "mmultiscripts", "mprescripts", "none", "mtable", "mtr", "mtd",
            "maligngroup", "malignmark", "semantics", "annotation", "annotation-xml", "mglyph",
        )
        val SAFE_MATHML_ATTRIBUTES = arrayOf(
            "display", "alttext", "encoding", "mathvariant", "mathsize", "mathcolor",
            "stretchy", "symmetric", "fence", "separator", "form", "movablelimits",
            "accent", "accentunder", "displaystyle", "scriptlevel", "linethickness",
            "columnalign", "rowalign", "columnspacing", "rowspacing", "columnspan",
            "rowspan", "bevelled", "close", "open", "notation",
        )
    }
}

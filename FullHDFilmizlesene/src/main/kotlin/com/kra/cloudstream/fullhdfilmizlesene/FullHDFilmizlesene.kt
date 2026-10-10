package com.kra.cloudstream.fullhdfilmizlesene

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Independent provider: no dependency on Dizilla code. */
class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override var sequentialMainPage = true
    override val mainPage = mainPageOf("$" + "mainUrl/" to "Öne Çıkan Filmler")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, referer = "$" + "mainUrl/").document
        val items = doc.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!isMovieUrl(href)) return@mapNotNull null
            a.toSearchResult(href)
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val response = app.get("$" + "mainUrl/autocomplete/q.php?q=" + query.urlEncoded(),
            referer = "$" + "mainUrl/", headers = mapOf("Accept" to "application/json, text/plain, */*"))
        val results = mutableListOf<SearchResponse>()
        runCatching { collectJsonResults(mapper.readTree(response.text), results) }
        if (results.isEmpty()) response.document.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (isMovieUrl(href)) a.toSearchResult(href)?.let(results::add)
        }
        if (results.isEmpty()) app.get("$" + "mainUrl/?s=" + query.urlEncoded(), referer = "$" + "mainUrl/")
            .document.select("a[href]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (isMovieUrl(href)) a.toSearchResult(href)?.let(results::add)
            }
        return results.distinctBy { it.url }.take(30)
    }

    private fun collectJsonResults(n: JsonNode, out: MutableList<SearchResponse>) {
        when {
            n.isArray -> n.forEach { collectJsonResults(it, out) }
            n.isObject -> {
                val url = textField(n, "url", "link", "permalink", "href")
                val title = textField(n, "title", "name", "label", "text")
                val poster = textField(n, "poster", "image", "thumbnail", "img")
                if (!url.isNullOrBlank() && !title.isNullOrBlank()) {
                    val absolute = fixUrlNull(url)
                    if (absolute != null && isMovieUrl(absolute))
                        out.add(newMovieSearchResponse(title.decodeHtml(), absolute, TvType.Movie) {
                            posterUrl = poster?.let { fixUrlNull(it) }
                        })
                }
                n.fields().forEachRemaining { (_, child) -> if (child.isContainerNode) collectJsonResults(child, out) }
            }
        }
    }

    private fun textField(n: JsonNode, vararg keys: String): String? =
        keys.asSequence().mapNotNull { n.get(it)?.takeIf { v -> v.isValueNode }?.asText() }
            .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val d = app.get(url, referer = "$" + "mainUrl/").document
        val title = d.selectFirst("h1")?.text()?.trim()
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: d.title().substringBefore("|").substringBefore(" - ").trim()
        if (title.isBlank()) return null
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: d.selectFirst("img")?.let { imageUrl(it) }
        val plot = d.selectFirst("meta[name=description]")?.attr("content")
            ?: d.selectFirst("[itemprop=description], .description, .film-aciklama")?.text()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(title + " " + d.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot?.trim()
            this.year = year
            this.tags = d.select("a[rel=tag], .genre a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val d = app.get(data, referer = "$" + "mainUrl/").document
        val urls = linkedSetOf<String>()
        d.select("iframe[src], video[src], source[src]").forEach { e ->
            fixUrlNull(e.attr("src"))?.takeIf { isCandidateEmbed(it) }?.let(urls::add)
        }
        parseScx(d.html()).forEach { encoded ->
            decodeEntry(encoded)?.let { fixUrlNull(it) }?.takeIf { isCandidateEmbed(it) }?.let(urls::add)
        }
        urls.forEach { link -> runCatching { loadExtractor(link, data, subtitleCallback, callback) } }
        return urls.isNotEmpty()
    }

    private fun parseScx(html: String): List<String> {
        val m = Regex("""\bscx\s*=\s*\{""").find(html) ?: return emptyList()
        val start = html.indexOf('{', m.range.first)
        var depth = 0; var quoted = false; var quote = '\u0000'; var escaped = false; var end = -1
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quoted = false
                continue
            }
            if (c == '"' || c == '\'') { quoted = true; quote = c; continue }
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) { end = i + 1; break } }
        }
        if (end <= start) return emptyList()
        return Regex("""["'](?:tr|en|\d+)["']\s*:\s*["']([^"']+)["']""")
            .findAll(html.substring(start, end)).map { it.groupValues[1] }.filter { it.length > 12 }.toList()
    }

    private fun decodeEntry(s: String): String? = runCatching {
        val rot = s.map { c -> when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        } }.joinToString("")
        val normalized = rot.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()

    private fun isMovieUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return host.endsWith("fullhdfilmizlesene.now", true) && path.isNotBlank() && path != "/" &&
            !path.contains("kategori") && !path.contains("autocomplete") && !path.contains("search")
    }

    private fun isCandidateEmbed(url: String): Boolean =
        url.startsWith("http") && runCatching { URI(url).host?.isNotBlank() == true }.getOrDefault(false)

    private fun Element.toSearchResult(href: String): SearchResponse? {
        val card = closest("article, li, .film, .movie, .card, .item") ?: parent() ?: this
        val image = selectFirst("img") ?: card.selectFirst("img")
        val title = listOf(selectFirst("h2, h3, h4")?.text(), card.selectFirst("h2, h3, h4")?.text(),
            attr("title"), image?.attr("alt"), attr("aria-label"), text())
            .map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() && it.length > 1 } ?: return null
        return newMovieSearchResponse(title.decodeHtml(), href, TvType.Movie) {
            posterUrl = image?.let { imageUrl(it) }
        }
    }

    private fun imageUrl(img: Element): String? =
        listOf("data-src", "data-lazy-src", "data-original", "src").asSequence()
            .map { img.attr(it).trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:image", true) }
            ?.let { fixUrlNull(it) }

    private fun String.urlEncoded() = java.net.URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    private fun String.decodeHtml() = org.jsoup.parser.Parser.unescapeEntities(this, false)
}

package com.kra.cloudstream.hatycanlitribun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Beta adapter for public CanlıTribün mirror family. APK source chain is not yet independently verified. */
class HatyCanliTribun : MainAPI() {
    override var mainUrl = "https://www.canlitribun938.top"
    override var name = "Haty CanlıTribün"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/canli-mac-izle/" to "Canlı maçlar")

    private val hosts = listOf(
        "https://www.canlitribun938.top/canli-mac-izle/",
        "https://www.canlitribun938.top/",
        "https://canlitribun938.top/",
        "https://www.canlitribun839.top/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(request.name, firstCatalog(request.data)?.second.orEmpty(), hasNext = false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return firstCatalog(mainUrl)?.second.orEmpty().filter { it.name.contains(query, true) }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (!isSiteUrl(url)) return null
        val doc = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull() ?: return null
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: URI(url).path.orEmpty().trim('/').substringAfterLast('/').replace('-', ' ').takeIf { it.isNotBlank() } ?: return null
        val rawPoster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = rawPoster.takeIf { it.isNotBlank() }?.let { resolve(doc.baseUri(), it) }
            plot = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
        if (!isSiteUrl(data)) return false
        val doc = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull() ?: return false
        var emitted = false
        for (candidate in mediaCandidates(doc)) {
            when {
                candidate.contains(".m3u8", true) -> {
                    callback.invoke(newExtractorLink(name, "CanlıTribün HLS", candidate, type = ExtractorLinkType.M3U8) { referer = data })
                    emitted = true
                }
                candidate.contains(".mpd", true) -> {
                    callback.invoke(newExtractorLink(name, "CanlıTribün DASH", candidate, type = ExtractorLinkType.DASH) { referer = data })
                    emitted = true
                }
                else -> if (loadExtractor(candidate, data, subtitleCallback, callback)) emitted = true
            }
        }
        return emitted
    }

    private suspend fun firstCatalog(preferred: String): Pair<String, List<SearchResponse>>? {
        for (candidate in (listOf(preferred) + hosts).distinct()) {
            val doc = runCatching { app.get(candidate).document }.getOrNull() ?: continue
            val parsed = parseCatalog(doc)
            if (parsed.isNotEmpty()) return doc.baseUri().ifBlank { candidate } to parsed
        }
        return null
    }
    fun parseCatalog(doc: Document): List<SearchResponse> {
        val items = linkedMapOf<String, SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) return@forEach
            val href = resolve(doc.baseUri(), raw)
            if (!isSiteUrl(href) || URI(href).path.orEmpty().trim('/').isBlank()) return@forEach
            val title = titleOf(a)
            if (title.length < 2 || !looksRelevant(href, title, a)) return@forEach
            val img = a.selectFirst("img")
            val rawPoster = img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-lazy-src").orEmpty() }.ifBlank { img?.attr("src").orEmpty() }
            val poster = rawPoster.takeIf { it.isNotBlank() }?.let { resolve(doc.baseUri(), it) }
            items.putIfAbsent(href, newLiveSearchResponse(title, href) { posterUrl = poster })
        }
        return items.values.toList()
    }
    private fun mediaCandidates(doc: Document): Set<String> {
        val result = linkedSetOf<String>()
        doc.select("iframe[src], video[src], source[src], embed[src], [data-src], [data-url], [data-hls], [data-stream]").forEach { el ->
            for (attr in listOf("src", "data-src", "data-url", "data-hls", "data-stream")) {
                val raw = el.attr(attr).trim()
                if (raw.isNotBlank()) resolve(doc.baseUri(), raw).takeIf(::isHttpUrl)?.let(result::add)
            }
            Regex("""https?://[^'""\\s)<>]+""").findAll(el.attr("onclick")).map { it.value }.forEach(result::add)
        }
        Regex("""https?:\\?/\\?/[^""'\\s<>]+?\\.(?:m3u8|mpd)(?:\\?[^""'\\s<>]*)?""")
            .findAll(doc.html()).map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .filter(::isHttpUrl).forEach(result::add)
        return result
    }
    private fun titleOf(a: Element): String =
        (a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: a.attr("aria-label").takeIf { it.isNotBlank() }
            ?: a.text()).trim()
    private fun looksRelevant(url: String, title: String, a: Element): Boolean {
        val source = (URI(url).path.orEmpty() + " " + title + " " + a.className() + " " + a.parent()?.attr("class").orEmpty()).lowercase()
        return listOf("mac", "match", "canli", "live", "event", "spor", "futbol", "basketbol", "voleybol", "tenis", "league", "vs", "izle", "yayın").any(source::contains)
    }
    private fun isSiteUrl(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase().removePrefix("www.")
        Regex("""^canlitribun\\d+\\.(top|live)$""").matches(host)
    }.getOrDefault(false)
    private fun resolve(base: String, value: String): String = runCatching { URI(base).resolve(value).toString() }.getOrElse { value }
    private fun isHttpUrl(url: String) = url.startsWith("https://", true) || url.startsWith("http://", true)
}
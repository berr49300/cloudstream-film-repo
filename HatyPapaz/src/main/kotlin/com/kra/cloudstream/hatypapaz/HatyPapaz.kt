package com.kra.cloudstream.hatypapaz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

/**
 * APK evidence: PapazRepository and PapazBaseUrlRepository. Public discovery
 * currently surfaces papazsports.co; this is a beta HTML adapter, not proof
 * that it is the APK's active base URL or that streams are playable.
 * It only follows event/channel links and media/embed URLs explicitly returned
 * by page HTML. It does not manufacture stream paths or bypass access controls.
 */
class HatyPapaz : MainAPI() {
    override var mainUrl = "https://papazsports.co"
    override var name = "Haty Papaz"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/" to "Maçlar", "$mainUrl/" to "Kanallar")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val response = app.get(request.data)
        val items = linkedMapOf<String, SearchResponse>()
        response.document.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!isSiteUrl(href) || href.trimEnd('/') == mainUrl) return@forEach
            val title = titleOf(a)
            if (title.length < 3 || title.equals("Maçlar", true) || title.equals("Kanallar", true)) return@forEach
            if (!looksLikeEventOrChannel(href, title)) return@forEach
            val poster = fixUrlNull(a.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } })
            items.putIfAbsent(href, newLiveSearchResponse(title, href) { posterUrl = poster })
        }
        return newHomePageResponse(request.name, items.values.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse? {
        if (!isSiteUrl(url)) return null
        val response = app.get(url, referer = "$mainUrl/")
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = poster
            plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        if (!isSiteUrl(data)) return false
        val response = app.get(data, referer = "$mainUrl/")
        val candidates = linkedSetOf<String>()
        response.document.select("iframe[src], video[src], source[src]").forEach {
            fixUrlNull(it.attr("src"))?.let(candidates::add)
        }
        Regex("""https?:\\?/\\?/[^"\\s<>]+?\\.(?:m3u8|mpd)(?:\\?[^"\\s<>]*)?""")
            .findAll(response.text).map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .forEach(candidates::add)
        for (candidate in candidates) {
            if (candidate.contains(".m3u8", true) || candidate.contains(".mpd", true)) {
                callback.invoke(com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = name, name = "Papaz", url = candidate,
                    type = if (candidate.contains(".mpd", true))
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH
                    else com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                ) { referer = data })
            } else if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                loadExtractor(candidate, data, subtitleCallback, callback)
            }
        }
        return candidates.isNotEmpty()
    }

    private fun titleOf(a: Element): String =
        (a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: a.attr("aria-label").takeIf { it.isNotBlank() }
            ?: a.text()).trim()

    private fun isSiteUrl(url: String): Boolean =
        runCatching { URI(url).host?.equals("papazsports.co", true) == true }.getOrDefault(false)

    private fun looksLikeEventOrChannel(url: String, title: String): Boolean {
        val text = (URI(url).path.orEmpty() + " " + title).lowercase()
        return listOf("mac", "match", "event", "kanal", "canli", "live", "stream", "futbol",
            "basketbol", "voleybol", "tenis", "league", "vs").any(text::contains)
    }
}
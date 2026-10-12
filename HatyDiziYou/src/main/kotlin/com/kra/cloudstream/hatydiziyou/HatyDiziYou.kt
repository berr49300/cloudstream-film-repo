package com.kra.cloudstream.hatydiziyou

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Beta adapter for the currently observed DiziYou host.
 *
 * APK evidence confirms a dedicated DiziyouRepository and stream resolver.
 * Public inspection confirms current detail/episode route shapes, but the
 * episode embed currently returns 403 in the research environment. Therefore
 * this adapter only consumes links actually present in returned HTML and
 * delegates accessible iframe URLs to CloudStream extractors. It does not
 * synthesize stream or subtitle URLs from the older DiziYou implementation.
 */
class HatyDiziYou : MainAPI() {
    override var mainUrl = "https://diziyou.net"
    override var name = "Haty DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/" to "DiziYou")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data).document
        val items = linkedMapOf<String, SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!sameHost(href) || !isSeriesUrl(href)) return@forEach
            val title = cardTitle(a)
            if (title.isBlank()) return@forEach
            val poster = fixUrlNull(a.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
            })
            items.putIfAbsent(href, newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, items.values.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse? {
        if (!sameHost(url) || !isSeriesUrl(url)) return null
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            })
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
        val episodes = linkedMapOf<String, Episode>()
        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!sameHost(href) || !isEpisodeUrl(href)) return@forEach
            val label = cardTitle(a).ifBlank { href.substringAfterLast('/').replace('-', ' ') }
            episodes.putIfAbsent(href, newEpisode(href) { name = label })
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        if (!sameHost(data) || !isEpisodeUrl(data)) return false
        val doc = app.get(data, referer = "$mainUrl/").document
        val embeds = doc.select("iframe[src], video[src], source[src]")
            .mapNotNull { fixUrlNull(it.attr("src")) }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
        for (embed in embeds) loadExtractor(embed, data, subtitleCallback, callback)
        return embeds.isNotEmpty()
    }

    private fun cardTitle(a: Element): String =
        (a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: a.text()).trim()

    private fun sameHost(url: String): Boolean =
        runCatching { URI(url).host?.equals("diziyou.net", ignoreCase = true) == true }
            .getOrDefault(false)

    private fun isEpisodeUrl(url: String): Boolean =
        sameHost(url) && URI(url).path.orEmpty().contains("/bolum/", ignoreCase = true)

    private fun isSeriesUrl(url: String): Boolean =
        sameHost(url) && URI(url).path.orEmpty().contains("/dizi/", ignoreCase = true)
}
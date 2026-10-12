package com.kra.cloudstream.hatyturkish123

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * İlk bağımsız katalog adaptörü.
 *
 * İncelenen herkese açık sayfalar:
 * - https://ahs.turkish123.com/
 * - https://ahs.turkish123.com/series-list/
 * - https://ahs.turkish123.com/anne-yarisi/
 *
 * Site yapısı değişebileceği için yalnızca HTML'de bulunan bağlantılar kullanılır.
 * Arama endpoint'i veya oynatıcı URL'si tahmin edilmez.
 */
class HatyTurkish123 : MainAPI() {
    override var mainUrl = "https://ahs.turkish123.com"
    override var name = "Haty Turkish123"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf(
        "$mainUrl/series-list/" to "Diziler",
        "$mainUrl/" to "Ana sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination URL formatı doğrulanmadığından yalnızca doğrulanmış ilk sayfa açılır.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val document = app.get(request.data).document
        val items = linkedMapOf<String, SearchResponse>()
        document.select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            if (!isSeriesCandidate(href)) return@forEach
            val title = (anchor.selectFirst("img")?.attr("alt")
                ?: anchor.attr("title").ifBlank { anchor.text() }).trim()
            if (title.isBlank() || title.length < 2) return@forEach
            val poster = fixUrlNull(anchor.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            })
            items.putIfAbsent(href, newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            })
        }
        return newHomePageResponse(request.name, items.values.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse? {
        if (!isSameSite(url)) return null
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
        val episodes = linkedMapOf<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            if (!isSameSite(href) || href == url || !isEpisodeCandidate(href)) return@forEach
            val label = anchor.text().trim().ifBlank {
                anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
            }
            if (label.isNotBlank()) episodes.putIfAbsent(href, Episode(href, label))
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
        if (!isSameSite(data)) return false
        val document = app.get(data, referer = "$mainUrl/").document
        val embeds = document.select("iframe[src], video[src], source[src]")
            .mapNotNull { fixUrlNull(it.attr("src")) }
            .distinct()
        for (embed in embeds) {
            if (embed.startsWith("https://") || embed.startsWith("http://")) {
                loadExtractor(embed, data, subtitleCallback, callback)
            }
        }
        return embeds.isNotEmpty()
    }

    private fun isSameSite(url: String): Boolean =
        runCatching { java.net.URI(url).host?.equals("ahs.turkish123.com", true) == true }.getOrDefault(false)

    private fun isSeriesCandidate(url: String): Boolean =
        isSameSite(url) && !url.contains("#") &&
            !url.contains("/series-list/") && !url.endsWith("/")

    private fun isEpisodeCandidate(url: String): Boolean =
        isSameSite(url) && (url.contains("episode", true) || url.contains("bolum", true) ||
            url.contains("season", true) || url.contains("part", true))
}

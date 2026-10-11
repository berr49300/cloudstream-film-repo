package com.kra.cloudstream.ddizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Independent first-pass provider for DDizi.
 *
 * Publicly observed routes:
 * - /diziler/{id}/{slug}: series detail with episode links
 * - /izle/{id}/{slug}.htm: episode page with a visible player iframe
 *
 * Search endpoints and player internals are intentionally not guessed.
 */
class DDizi : MainAPI() {
    override var mainUrl = "https://www.ddizi.tel"
    override var name = "DDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/yeni-eklenenler8" to "Yeni Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "$mainUrl/").document
        val items = document.select("a[href]")
            .filter { isSameSite(it.attr("href")) && DDiziParser.isEpisodeUrl(fixUrl(it.attr("href"))) }
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                anchor.toEpisodeSearchResult(href)
            }
            .distinctBy { it.url }
            .take(30)

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The visible search form is present, but its submission route/field name
        // has not been verified from a live form submission. Do not guess an API.
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = app.get(url, referer = "$mainUrl/").document

        val seriesUrl = if (DDiziParser.isEpisodeUrl(url)) {
            page.select("a[href]").firstNotNullOfOrNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@firstNotNullOfOrNull null
                val text = anchor.text().trim()
                if (DDiziParser.isSeriesUrl(href) &&
                    (text.contains("dizi sayfas", true) || text.contains("diziye git", true) ||
                        anchor.parents().any { it.className().contains("breadcrumb", true) })
                ) href else null
            } ?: page.select("a[href]").mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull { DDiziParser.isSeriesUrl(it) }
        } else {
            url
        } ?: return null

        val seriesPage = if (seriesUrl == url) page else
            app.get(seriesUrl, referer = "$mainUrl/").document

        val rawTitle = seriesPage.selectFirst("h1")?.text()?.trim()
            ?: seriesPage.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: seriesPage.title().substringBefore("|").trim()
        val title = DDiziParser.cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = seriesPage.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: seriesPage.selectFirst("img[src], img[data-src]")?.let { imageUrl(it) }
        val plot = seriesPage.selectFirst("meta[name=description]")?.attr("content")
            ?: seriesPage.selectFirst(".ozet, .summary, .description, [itemprop=description]")?.text()

        val episodeAnchors = seriesPage.select("a[href]")
            .filter { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@filter false
                DDiziParser.isEpisodeUrl(href) &&
                    !href.equals(seriesUrl, ignoreCase = true) &&
                    anchor.text().isNotBlank()
            }
            .distinctBy { fixUrl(it.attr("href")) }

        val episodes = episodeAnchors.mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val episodeTitle = anchor.text().trim().ifBlank {
                anchor.selectFirst("img")?.attr("alt").orEmpty()
            }.ifBlank { "Bölüm" }
            newEpisode(href) {
                name = episodeTitle
                episode = DDiziParser.episodeNumber(episodeTitle)
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot?.trim()
            this.tags = seriesPage.select("a[rel=tag], .genre a, .tur a")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, referer = "$mainUrl/")
        val document = page.document
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src]")
            .forEach { element ->
                listOf("src", "data-src", "data-url").forEach { attr ->
                    val raw = element.attr(attr).trim()
                    if (raw.isNotBlank()) {
                        val absolute = fixUrlNull(raw)
                        if (absolute != null && isAllowedPlayerUrl(absolute)) candidates.add(absolute)
                    }
                }
            }

        // A few pages expose the selected part/player as a normal link instead of an iframe.
        document.select("a[href]").forEach { anchor ->
            val raw = anchor.attr("href").trim()
            val absolute = fixUrlNull(raw) ?: return@forEach
            if (isAllowedPlayerUrl(absolute) && !DDiziParser.isSeriesUrl(absolute) &&
                !DDiziParser.isEpisodeUrl(absolute)
            ) candidates.add(absolute)
        }

        var emitted = 0
        candidates.forEach { candidate ->
            val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
            runCatching {
                if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd")) {
                    val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else null
                    callback(newExtractorLink(name, name, candidate, type) {
                        referer = data
                        quality = Qualities.Unknown.value
                    })
                    emitted++
                } else {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        emitted++
                    }
                }
            }
        }
        return emitted > 0
    }

    private fun Element.toEpisodeSearchResult(href: String): SearchResponse? {
        val title = listOf(
            selectFirst("h2, h3, h4, [class*=title]")?.text(),
            selectFirst("img")?.let { it.attr("alt").ifBlank { it.attr("title") } },
            attr("title"),
            text()
        ).map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("iframe", true) }
            ?: return null

        val poster = selectFirst("img[src], img[data-src]")?.let { imageUrl(it) }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    private fun isSameSite(rawUrl: String): Boolean {
        val absolute = fixUrlNull(rawUrl) ?: return false
        val host = runCatching { URI(absolute).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "ddizi.tel" || host.endsWith(".ddizi.tel")
    }

    private fun isAllowedPlayerUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.isNotBlank() && host != "www.ddizi.tel" && host != "ddizi.tel" &&
            !host.endsWith(".google.com") && !host.contains("facebook")
    }
}

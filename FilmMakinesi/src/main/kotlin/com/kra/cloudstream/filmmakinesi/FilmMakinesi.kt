/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Minimal FilmMakinesi provider. Player resolution follows the small,
 * proven CloseLoad/Rapid JavaScript-resolution path.
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Small sequential requests are friendlier to the site than parallel scraping.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "$mainUrl/filmler-1/" to "Son Filmler",
        "$mainUrl/yabanci-dizi-izle-1/" to "Diziler",
        "$mainUrl/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "$mainUrl/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu",
        "$mainUrl/tur/komedi-fm1/film/" to "Komedi",
        "$mainUrl/tur/korku-fm2/film/" to "Korku",
        "$mainUrl/ulke/turkiye-fm4/" to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/sayfa/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("div.item-relative > a.item").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("a[href*='/sayfa/${page + 1}/']") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/arama/",
            params = mapOf("s" to query),
            referer = "$mainUrl/"
        ).document
        return document.select("div.item-relative > a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("data-title")
            .ifBlank { selectFirst("div.title")?.text().orEmpty() }
            .trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )
        val score = attr("data-score")
        val year = selectFirst("div.info span")?.text()?.trim()?.take(4)?.toIntOrNull()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("h1.title")?.ownText()?.removeSuffix(" izle")?.trim()
            ?: document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("img.cover-img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }) ?: fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.info-description p")?.text()?.trim()
        val year = document.selectFirst("h1.title span.date a")?.text()?.trim()?.toIntOrNull()
        val score = document.selectFirst("div.imdb b")?.text()?.trim()
        val tags = document.select("div.info div.type a").map { it.text().trim() }
        val trailer = document.selectFirst("a.trailer-button")?.attr("data-video_url")
            ?.takeIf { it.isNotBlank() }
            ?: document.select("iframe[data-src*='youtube']").firstOrNull()?.attr("data-src")
                ?.takeIf { it.isNotBlank() }

        if (url.contains("/dizi/")) {
            val episodes = document.select("#seasons a.item-ep, a.item-ep").mapNotNull { element ->
                val episodeUrl = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                if (!episodeUrl.contains("/bolum-")) return@mapNotNull null
                val season = Regex("""/sezon-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("""/bolum-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(episodeUrl) {
                    name = element.selectFirst("div.ep-details span")?.text()?.trim()
                        ?: element.selectFirst("div.ep-title")?.text()?.trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.score = Score.from10(score)
                this.tags = tags
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.score = Score.from10(score)
            this.tags = tags
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }
            .getOrNull() ?: return false

        // The site's player tabs keep their URLs in data-video_url; some pages use iframes.
        val candidates = (
            document.select("div.video-parts a[data-video_url], a[data-video_url]")
                .map { it.attr("data-video_url") } +
            document.select("div.player--area iframe, div.player-div iframe, iframe")
                .map { it.attr("data-src").ifBlank { it.attr("src") } }
        )
            .map { if (it.startsWith("//")) "https:$it" else it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { it.contains("youtube", ignoreCase = true) }
            .distinct()

        var emitted = false
        val resolver = FilmMakinesiPlayerResolver()
        for (playerUrl in candidates) {
            val host = runCatching { URI(playerUrl).host.orEmpty().lowercase() }.getOrDefault("")
            runCatching {
                if (host.startsWith("closeload") || host.startsWith("rapid") ||
                    playerUrl.contains("/rplayer/") || playerUrl.contains("/playerr/")) {
                    resolver.getUrl(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        emitted = true
                    }
                } else {
                    loadExtractor(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                        emitted = true
                    }
                }
            }
        }
        return emitted
    }
}

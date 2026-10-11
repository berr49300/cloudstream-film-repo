package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * FilmMakinesi provider.
 *
 * The public site currently exposes /kesfet/ and category routes. Selectors are
 * deliberately tolerant of card markup changes; unsupported page shapes return
 * empty results rather than manufacturing titles or URLs.
 */
class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Öne Çıkanlar",
        "$mainUrl/tur/aksiyon-fm1/film/" to "Aksiyon Filmleri",
        "$mainUrl/tur/dram-fm1/film/" to "Dram Filmleri",
        "$mainUrl/kesfet/" to "Keşfet"
    )

    private fun absolute(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { fixUrlNull(value.trim()) }.getOrNull()
    }

    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null
        return absolute(
            img.attr("data-src").ifBlank {
                img.attr("data-lazy-src").ifBlank {
                    img.attr("data-original").ifBlank { img.attr("src") }
                }
            }
        )
    }

    private fun Element.cardTitle(): String? {
        val candidate = selectFirst("h2, h3, h4, .title, [class*=title]")?.text()
            ?.trim()?.takeIf { it.isNotBlank() }
        return candidate
            ?: selectFirst("img")?.let { it.attr("alt").ifBlank { it.attr("title") } }
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a[title]")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a[href]")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isDetailUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        return path.isNotBlank() && path != "/" &&
            !path.startsWith("/tur/") && !path.startsWith("/kanallar") &&
            !path.startsWith("/kesfet") && !path.startsWith("/yardim") &&
            !path.startsWith("/page/") && !path.startsWith("/wp-")
    }

    private fun inferType(url: String, element: Element? = null): TvType {
        val hint = (url + " " + element?.text().orEmpty() + " " +
            element?.attr("class").orEmpty()).lowercase()
        return if (hint.contains("dizi") || hint.contains("sezon") ||
            hint.contains("bölüm") || hint.contains("episode") ||
            hint.contains("/series/") || hint.contains("/tv/")) TvType.TvSeries
        else TvType.Movie
    }

    private fun Element.toResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = absolute(a.attr("href")) ?: return null
        if (!isDetailUrl(href) || href == "$mainUrl/") return null
        val title = cardTitle()?.takeIf { it.length > 1 } ?: return null
        val type = inferType(href, this)
        return if (type == TvType.TvSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = this@toResult.poster()
        } else newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = this@toResult.poster()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            val nextUrl = when {
                request.data.endsWith("/") -> "${request.data}page/$page/"
                else -> "${request.data}/page/$page/"
            }
            val document = runCatching { app.get(nextUrl, referer = "$mainUrl/").document }.getOrNull()
                ?: return newHomePageResponse(request.name, emptyList(), false)
            return newHomePageResponse(request.name, parseCards(document), hasNext = parseCards(document).isNotEmpty())
        }
        val document = app.get(request.data, referer = "$mainUrl/").document
        val items = parseCards(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val preferred = document.select(
            "article, .film-card, .movie-card, .series-card, .poster-item, " +
            ".film-item, .movie-item, .item, .post, .result-item, .movie"
        )
        val candidates = if (preferred.isNotEmpty()) preferred else
            document.select("main a[href], #content a[href], .content a[href]")
                .filter { it.selectFirst("img") != null }
        return candidates.mapNotNull { it.toResult() }.distinctBy { it.url }.take(80)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.urlEncoded()
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/kesfet/?q=$encoded",
            "$mainUrl/?search=$encoded"
        )
        for (url in urls) {
            val document = runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()
                ?: continue
            val results = parseCards(document)
            if (results.isNotEmpty()) return results.take(30)
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = absolute(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        )
        val description = document.selectFirst(
            "[itemprop=description], .description, .plot, .synopsis, meta[name=description]"
        )?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()?.takeIf { it.isNotBlank() }
        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(document.select("dt, dd, .year, .meta, .info").text())?.value?.toIntOrNull()
        val rating = Regex("""(?:IMDb|IMDB|puan)\D{0,12}(\d(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.let { (it * 10).toInt() }
        val tags = document.select("a[href*=tur], a[rel=tag], .genres a, .genre a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val type = inferType(url, document.body())
        val trailer = document.selectFirst("iframe[title*=Fragman], iframe[src*=youtube], iframe[data-src*=youtube]")
            ?.let { absolute(it.attr("data-src").ifBlank { it.attr("src") }) }

        // Episode rows are included only when the page actually contains episode links.
        if (type == TvType.TvSeries) {
            val episodes = document.select(
                ".episode-list a[href], .episodes a[href], .seasons a[href], " +
                "a[href*=bolum], a[href*=episode], a[href*=sezon]"
            ).mapNotNull { a ->
                val epUrl = absolute(a.attr("href")) ?: return@mapNotNull null
                if (epUrl == url || !isDetailUrl(epUrl)) return@mapNotNull null
                val epTitle = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
                if (epTitle.isBlank()) return@mapNotNull null
                val season = Regex("""(\d+)\s*\.??\s*sezon""", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\s*\.??\s*bölüm""", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""S(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)
                        .find(epTitle)?.groupValues?.getOrNull(2)?.toIntOrNull()
                newEpisode(epUrl) {
                    name = epTitle
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    plot = description
                    this.year = year
                    this.tags = tags
                    this.rating = rating
                    addTrailer(trailer)
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.rating = rating
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val frames = document.select(
            ".player iframe, .player-div iframe, .video-player iframe, " +
            "iframe[src], iframe[data-src], iframe[data-lazy-src]"
        )
        val frame = frames.asSequence().mapNotNull {
            absolute(it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-lazy-src") } })
        }.firstOrNull { candidate ->
            candidate != "$mainUrl/" && !candidate.contains("youtube.com/embed") &&
                !candidate.contains("google.com/recaptcha")
        } ?: return false

        loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}

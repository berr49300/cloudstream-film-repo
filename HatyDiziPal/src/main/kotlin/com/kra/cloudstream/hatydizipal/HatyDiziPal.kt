package com.kra.cloudstream.hatydizipal

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Independent beta adapter informed by public provider route/player references.
 * Host, search response and player integration have not been validated against
 * fresh raw responses here; actual CloudStream playback is unverified.
 *
 * It consumes only IDs, iframe URLs, manifests and subtitle tracks exposed by
 * returned pages/player config. It does not fabricate content IDs or bypass
 * authentication, CAPTCHA, region controls or DRM.
 */
class HatyDiziPal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal14.com"
    override var name = "Haty DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = if (page <= 1) request.data else {
            val separator = if (request.data.contains("?")) "&" else "?"
            request.data + separator + "page=$page"
        }
        val doc = app.get(target).document
        val results = doc.select("a[href*='/dizi/'], a[href*='/film/']")
            .mapNotNull(::parseSearchElement)
            .distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    /** Pure DOM parser exposed for fixture-backed tests. */
    fun parseSearchElement(element: Element): SearchResponse? {
        val item = DiziPalHtmlParser.parseCatalogElement(element, mainUrl) ?: return null
        return if (item.isSeries) {
            newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                posterUrl = item.posterUrl
                year = item.year
            }
        } else {
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                posterUrl = item.posterUrl
                year = item.year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val root = runCatching {
            app.get(
                "$mainUrl/api/search.php?q=$encoded",
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).parsedSafe<DiziPalSearchRoot>()
        }.getOrNull() ?: return emptyList()

        return root.results.orEmpty().mapNotNull { item ->
            val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = item.slug?.trim()?.trim('/')?.takeIf {
                it.isNotBlank() && !it.contains("..") && !it.contains("://")
            } ?: return@mapNotNull null
            val type = item.type.orEmpty().lowercase()
            val series = type == "series" || type == "tv" || type == "dizi"
            val movie = type == "movie" || type == "film"
            if (!series && !movie) return@mapNotNull null
            val section = if (series) "dizi" else "film"
            val url = "$mainUrl/$section/$slug"
            val poster = fixUrlNull(item.poster)
            if (series) newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            } else newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (!isSiteUrl(url)) return null
        return parseLoadMetadata(app.get(url).document, url)
    }

    /** Pure page-to-model mapping, separated from network access for tests. */
    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        if (!isSiteUrl(url)) return null
        val path = URI(url).path.orEmpty()
        val isSeries = path.contains("/dizi/")
        val isMovie = path.contains("/film/")
        if (!isSeries && !isMovie) return null

        val title = (doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim())
            ?.substringBefore(" izle")?.substringBefore(" - DiziPal")?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("div.cover img, div.poster img, img.poster")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            })
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("div.summary, div.overview, p.description")?.text()?.trim()
        val year = doc.selectFirst("span.year, div.year, a[href*='yil/']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        if (isSeries) {
            val episodes = DiziPalHtmlParser.parseEpisodeEntries(doc, url).map { item ->
                newEpisode(item.url) {
                    name = item.title
                    season = item.season
                    episode = item.episode
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isSiteUrl(data)) return false
        val response = app.get(data, referer = "$mainUrl/")
        val candidates = linkedSetOf<String>()
        response.document.select("iframe[src], video[src], source[src]").forEach { element ->
            fixUrlNull(element.attr("src"))?.let(candidates::add)
        }

        // Decode only a literal iframe value exposed by this player family.
        response.document.select("script").forEach { script ->
            val encoded = Regex("""const\s+encodedContent\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")
                .find(script.data())?.groupValues?.get(1) ?: return@forEach
            val decoded = runCatching { String(base64DecodeArray(encoded), Charsets.UTF_8) }
                .getOrNull() ?: return@forEach
            Regex("""src\s*=\s*['"]([^'"]+)['"]""").find(decoded)?.groupValues?.get(1)
                ?.let { resolveUrl(data, it) }?.let(candidates::add)
        }

        Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mpd)(?:\?[^"'\s<>]*)?""")
            .findAll(response.text)
            .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .forEach(candidates::add)

        var found = false
        for (candidate in candidates) {
            val host = runCatching { URI(candidate).host.orEmpty() }.getOrDefault("")
            if (host.equals("videoplay.vip", ignoreCase = true)) {
                if (resolveVideoPlay(candidate, data, subtitleCallback, callback)) found = true
            } else if (candidate.contains(".m3u8", true) || candidate.contains(".mpd", true)) {
                val type = if (candidate.contains(".mpd", true)) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                callback.invoke(newExtractorLink(name, name, candidate, type = type) { referer = data })
                found = true
            } else if (candidate.startsWith("https://") || candidate.startsWith("http://")) {
                if (loadExtractor(candidate, data, subtitleCallback, callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveVideoPlay(
        playerUrl: String,
        episodePageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val page = app.get(playerUrl, referer = episodePageUrl).text
        val src = Regex("""const\s+src\s*=\s*['"]([^'"]+)['"]""")
            .find(page)?.groupValues?.get(1) ?: return@runCatching false
        val stream = resolveUrl(playerUrl, src)
        if (!stream.contains(".m3u8", true)) return@runCatching false

        val tracksJson = Regex("""const\s+tracksData\s*=\s*(\{.*?\})\s*;""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(page)?.groupValues?.get(1)
        if (tracksJson != null) runCatching {
            val subtitles = JSONObject(tracksJson).optJSONArray("subtitles")
            if (subtitles != null) for (i in 0 until subtitles.length()) {
                val track = subtitles.optJSONObject(i) ?: continue
                val source = track.optString("url").takeIf { it.isNotBlank() } ?: continue
                val id = track.optString("id")
                val language = track.optString("name").ifBlank {
                    track.optString("lang").ifBlank { "Unknown" }
                }
                val subtitleUrl = when {
                    source.startsWith("http://") || source.startsWith("https://") -> source
                    id.isNotBlank() -> "https://videoplay.vip/play.m3u8?id=" + URLEncoder.encode(id, "UTF-8") +
                        "&p=" + URLEncoder.encode(source, "UTF-8")
                    else -> resolveUrl(playerUrl, source)
                }
                subtitleCallback.invoke(SubtitleFile(language, subtitleUrl))
            }
        }
        callback.invoke(newExtractorLink(name, name, stream, type = ExtractorLinkType.M3U8) {
            referer = "https://videoplay.vip/"
            quality = Qualities.Unknown.value
        })
        true
    }.getOrDefault(false)

    private fun resolveUrl(base: String, candidate: String): String =
        runCatching { URI(base).resolve(candidate).toString() }.getOrElse { candidate }

    private fun isSiteUrl(url: String): Boolean = DiziPalHtmlParser.isSiteUrl(url)

    data class DiziPalSearchRoot(
        @JsonProperty("results") val results: List<DiziPalSearchResult>? = null
    )

    data class DiziPalSearchResult(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )
}
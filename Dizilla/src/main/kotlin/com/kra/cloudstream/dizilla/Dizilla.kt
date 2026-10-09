package com.kra.cloudstream.dizilla

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Dizilla catalogue provider.
 *
 * It parses publicly rendered HTML and publicly exposed iframe/media elements only.
 * It does not decrypt hidden page payloads, bypass access controls, or extract protected streams.
 */
class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenen Bölümler",
        "${mainUrl}/arsiv" to "Dizi Arşivi",
        "${mainUrl}/yabanci-dizi-izle" to "Yabancı Diziler",
        "${mainUrl}/anime-izle" to "Anime",
        "${mainUrl}/kdrama-izle" to "Kore Dizileri",
        "${mainUrl}/imdb-top-100" to "IMDb Top 100"
    )

    private fun isDizillaHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "dizilla.now" || host == "www.dizilla.now"
    }

    private fun safePageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val fixed = fixUrlNull(raw.trim()) ?: return null
        val uri = runCatching { URI(fixed) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("https", "http")) return null
        if (!isDizillaHost(fixed)) return null
        return fixed
    }

    private fun posterFor(element: Element): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { key -> image.attr(key).takeIf { it.isNotBlank() } }
            ?: return null
        return fixUrlNull(raw)
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val cards = doc.select(
            "div.serie-card, div.episode-card, .series-card, .show-card, " +
                "article.serie, article.series, a[href*='/dizi/']"
        )

        for (card in cards) {
            val link = if (card.tagName().equals("a", ignoreCase = true)) {
                card
            } else {
                card.selectFirst("a[href]") ?: continue
            }
            val href = safePageUrl(link.attr("href")) ?: continue
            if (!seen.add(href)) continue

            val title = listOf(
                card.selectFirst(".title")?.text(),
                card.selectFirst("h2")?.text(),
                card.selectFirst("h3")?.text(),
                link.attr("title"),
                link.selectFirst("img")?.attr("alt"),
                card.selectFirst("img")?.attr("alt"),
                link.text()
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
            if (title.isBlank()) continue

            results += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = posterFor(card)
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = safePageUrl(request.data) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val target = if (page <= 1) {
            base
        } else {
            val separator = if (base.contains("?")) "&" else "?"
            "$base${separator}page=$page"
        }

        val doc = app.get(target, headers = mapOf("Referer" to "$mainUrl/")).document
        val results = parseListing(doc)
        val hasNext = doc.selectFirst(
            "a[rel=next], a.next[href], .pagination a[aria-label*=next i][href]"
        ) != null
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        // This is a conventional, user-facing HTML search URL; no private API or hidden payload is used.
        val searchUrl = "$mainUrl/arama?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val doc = app.get(searchUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        val results = parseListing(doc).distinctBy { it.url }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = safePageUrl(url) ?: return null
        val doc = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        val title = listOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property='og:title']")?.attr("content"),
            doc.selectFirst("title")?.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.replace(Regex("""\s*[-|–]\s*Dizilla\s*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+İzle\s*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        )
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val yearText = listOf(
            doc.selectFirst(".year")?.text(),
            doc.selectFirst(".date")?.text(),
            doc.selectFirst("[itemprop=datePublished]")?.attr("content"),
            doc.selectFirst("meta[property='article:published_time']")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val year = Regex("""\b(?:19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val episodes = parseEpisodes(doc, pageUrl)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun parseEpisodes(doc: Document, currentUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val anchors = doc.select(
            "a[href*='-sezon-'], a[href*='-season-'], a[href*='-bolum'], " +
                "a[href*='-bölüm'], a[href*='/episode/']"
        )

        for (anchor in anchors) {
            val href = safePageUrl(anchor.attr("href")) ?: continue
            if (href == currentUrl || !seen.add(href)) continue
            val linkText = anchor.text().trim()
            val position = DizillaParser.parseEpisodePosition(href, linkText) ?: continue

            episodes += newEpisode(href) {
                name = linkText.takeIf { it.isNotBlank() }
                    ?: "${position.season}. Sezon ${position.episode}. Bölüm"
                season = position.season
                episode = position.episode
            }
        }

        return episodes
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = safePageUrl(data) ?: return false
        val doc = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        return inspectPublicMedia(doc, pageUrl, subtitleCallback, callback, allowOneNestedPage = true)
    }

    /**
     * Resolves only direct media URLs or iframe URLs exposed in the returned HTML.
     * One same-site embed page may be inspected to handle a visible, ordinary iframe wrapper.
     */
    private suspend fun inspectPublicMedia(
        doc: Document,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        allowOneNestedPage: Boolean
    ): Boolean {
        var found = false

        for (track in doc.select("video track[src], track[src]")) {
            val trackUrl = fixUrlNull(track.attr("src")) ?: continue
            val label = track.attr("label").ifBlank {
                track.attr("srclang").ifBlank { "Subtitle" }
            }
            subtitleCallback(SubtitleFile(label, trackUrl))
        }

        val mediaElements = doc.select("video[src], video source[src], source[src]")
        for (element in mediaElements) {
            val mediaUrl = fixUrlNull(element.attr("src")) ?: continue
            val path = runCatching { URI(mediaUrl).path.orEmpty() }.getOrDefault("")
            val isHls = path.endsWith(".m3u8", ignoreCase = true)
            val isMp4 = path.endsWith(".mp4", ignoreCase = true)
            if (!isHls && !isMp4) continue

            callback(
                newExtractorLink(
                    source = name,
                    name = if (isHls) "$name HLS" else "$name MP4",
                    url = mediaUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        for (frame in doc.select("iframe[src], iframe[data-src]")) {
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            val frameUrl = fixUrlNull(raw) ?: continue
            val uri = runCatching { URI(frameUrl) }.getOrNull() ?: continue
            if (uri.scheme !in setOf("https", "http")) continue

            if (isDizillaHost(frameUrl)) {
                if (allowOneNestedPage && frameUrl != referer) {
                    val nested = runCatching {
                        app.get(frameUrl, headers = mapOf("Referer" to referer)).document
                    }.getOrNull() ?: continue
                    found = inspectPublicMedia(
                        nested,
                        frameUrl,
                        subtitleCallback,
                        callback,
                        allowOneNestedPage = false
                    ) || found
                }
            } else {
                // CloudStream's standard extractors handle publicly exposed third-party embed pages.
                val loaded = runCatching {
                    loadExtractor(frameUrl, referer, subtitleCallback, callback)
                }.getOrDefault(false)
                found = loaded || found
            }
        }

        return found
    }
}

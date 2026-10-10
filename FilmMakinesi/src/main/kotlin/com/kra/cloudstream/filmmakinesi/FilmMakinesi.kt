/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * FilmMakinesi CloudStream provider.
 *
 * Public CloseLoad protocol behavior was cross-checked against the GPL-3.0
 * CloudStreamHub reference listed in THIRD_PARTY_NOTICES.md; this adapter is
 * independently adapted to this repository.
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class FilmMakinesiSource(
    val url: String,
    val label: String
)

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/filmler-1/" to "Son Eklenen Filmler",
        "$mainUrl/yabanci-dizi-izle-1/" to "Yabancı Diziler",
        "$mainUrl/tur/aksiyon-fmy54y/film/" to "Aksiyon Filmleri",
        "$mainUrl/kesfet/" to "Keşfet"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = canonicalSiteUrl(request.data) ?: "$mainUrl/filmler-1/"
        val requestedUrl = if (page <= 1) base else base.trimEnd('/') + "/sayfa/" + page + "/"
        val doc = runCatching { app.get(requestedUrl, headers = headersFor(base)).document }
            .getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = parseSearchResults(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val paths = listOf(
            "$mainUrl/arama/?s=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/arama/?q=$encoded"
        )

        for (searchUrl in paths) {
            val doc = runCatching { app.get(searchUrl, headers = headersFor("$mainUrl/")).document }
                .getOrNull() ?: continue
            val found = parseSearchResults(doc)
            if (found.isNotEmpty()) return found.distinctBy { it.url }.take(50)
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    internal fun parseSearchResults(doc: Document): List<SearchResponse> {
        val output = mutableListOf<SearchResponse>()
        val cards = doc.select(
            "a.item, a.slide, a[data-title], div.item-relative a[href], " +
                ".item-relative a.item, .film-item a[href], .movie-item a[href], " +
                ".dizi-item a[href], article a[href]"
        )

        cards.forEach { card ->
            parseSearchElement(card)?.let(output::add)
        }

        // Fallback for layout variants, but only accept canonical detail-page paths.
        if (output.isEmpty()) {
            doc.select("a[href]").forEach { anchor ->
                val url = absoluteUrl(anchor.attr("href"), mainUrl) ?: return@forEach
                if (!isContentUrl(url)) return@forEach
                parseSearchElement(anchor)?.let(output::add)
            }
        }
        return output.distinctBy { it.url }
    }

    internal fun parseSearchElement(element: Element): SearchResponse? {
        val anchor = if (element.tagName().equals("a", ignoreCase = true)) {
            element
        } else {
            element.selectFirst("a[href]") ?: return null
        }
        val url = absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(url)) return null

        val image = anchor.selectFirst("img") ?: element.selectFirst("img")
        val rawTitle = sequenceOf(
            element.attr("data-title"),
            anchor.attr("data-title"),
            element.selectFirst(".title, .item-title, h2, h3, h4")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !isGenericLabel(it) }
            ?: return null

        val title = Parser.unescapeEntities(rawTitle, false)
            .replace(Regex("""\s+"""), " ")
            .trim()
        val poster = image?.let {
            val raw = sequenceOf(
                it.attr("data-src"),
                it.attr("data-original"),
                it.attr("data-lazy-src"),
                it.attr("src"),
                it.attr("srcset").substringBefore(",").trim().substringBefore(" ")
            ).firstOrNull(String::isNotBlank)
            raw?.let { value -> absoluteUrl(value, url) }
        }
        val score = sequenceOf(
            element.attr("data-score"),
            anchor.attr("data-score"),
            element.selectFirst(".rating, .imdb-score span, [itemprop=ratingValue]")?.text()
        ).map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }

        return if (isSeriesUrl(url)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = absoluteUrl(url, mainUrl) ?: return null
        val response = runCatching {
            app.get(target, headers = headersFor("$mainUrl/"))
        }.getOrNull() ?: return null
        val doc = response.document

        val title = sequenceOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.title().substringBefore(" | ").substringBefore(" - ")
        ).map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("""\s*[-|]\s*FilmMakinesi.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { absoluteUrl(it, target) }
            ?: doc.selectFirst("img[data-src], img[src]")?.let { image ->
                absoluteUrl(image.attr("data-src").ifBlank { image.attr("src") }, target)
            }
        val plot = sequenceOf(
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst(".description, .info-content .description, .film-aciklama")?.text()
        ).map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }
        val metadataText = title + " " + doc.text()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(metadataText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val score = doc.selectFirst(".imdb-score span, .rating, [itemprop=ratingValue]")
            ?.let { it.attr("content").ifBlank { it.text() }.trim() }
        val tags = doc.select("a[href*='/tur/'], a[rel=tag], .genres a, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (isSeriesUrl(target)) {
            val episodes = parseEpisodes(doc, target)
            return newTvSeriesLoadResponse(title, target, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(score)
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, target, TvType.Movie, target) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(score)
            this.tags = tags
        }
    }

    internal fun parseEpisodes(doc: Document, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val candidates = doc.select(
            "a[href*='bolum'], a[href*='episode'], .episodes a[href], .episode-list a[href], " +
                ".bolumler a[href], .sezonlar a[href], .season-episodes a[href]"
        )

        candidates.forEach { anchor ->
            val href = absoluteUrl(anchor.attr("href"), pageUrl) ?: return@forEach
            if (href == pageUrl || !isSameSite(href)) return@forEach
            val container = anchor.closest(".episodes, .episode-list, .bolumler, .sezonlar, .season-episodes")
            val label = sequenceOf(
                anchor.attr("title"),
                anchor.selectFirst(".title, .episode-title")?.text(),
                anchor.text()
            ).map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }
                ?: urlEpisodeLabel(href)
                ?: return@forEach

            val urlAndLabel = label + " " + href
            val packed = Regex("""(?i)\bs(\d{1,2})\s*e(\d{1,3})\b""").find(urlAndLabel)
            val seasonByName = Regex("""(?i)(\d{1,2})\s*\.?\s*(?:sezon|season)""")
                .find(urlAndLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodeByName = Regex("""(?i)(\d{1,3})\s*\.?\s*(?:bölüm|bolum|episode|ep\.?)""")
                .find(urlAndLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val season = packed?.groupValues?.getOrNull(1)?.toIntOrNull() ?: seasonByName ?: 1
            val episode = packed?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: episodeByName
                ?: Regex("""(?i)(?:bolum|episode|ep)[-/ ]*(\d{1,3})""")
                    .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            if (container == null && !href.contains("bolum", ignoreCase = true) &&
                !href.contains("episode", ignoreCase = true) && packed == null && episodeByName == null) {
                return@forEach
            }

            episodes.add(newEpisode(href) {
                name = label.ifBlank { "Bölüm " + episode }
                this.season = season
                this.episode = episode
            })
        }

        return episodes.distinctBy { it.data }.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data, mainUrl) ?: return false
        val response = runCatching {
            app.get(pageUrl, headers = headersFor("$mainUrl/"))
        }.getOrNull() ?: return false
        val html = response.text
        val doc = response.document
        var found = false
        val emittedUrls = linkedSetOf<String>()
        val emittedMediaUrls = linkedSetOf<String>()

        suspend fun emitSubtitleTracks(sourceHtml: String, sourceUrl: String) {
            FilmMakinesiSubtitleParser.parse(sourceHtml, sourceUrl).forEach { track ->
                subtitleCallback(newSubtitleFile(track.lang, track.url) {
                    this.headers = headersFor(sourceUrl)
                })
            }
        }
        emitSubtitleTracks(html, pageUrl)

        val sources = parseSourceCandidates(doc, html, pageUrl)
        val closeLoad = FilmMakinesiCloseLoadExtractor()

        for (source in sources) {
            val sourceUrl = source.url
            if (sourceUrl in emittedUrls) continue
            emittedUrls.add(sourceUrl)

            if (isDirectMediaUrl(sourceUrl)) {
                val key = mediaDedupeKey(sourceUrl)
                if (emittedMediaUrls.add(key)) {
                    callback(newExtractorLink(
                        source = name,
                        name = displaySourceName(source.label, sourceUrl),
                        url = sourceUrl,
                        type = FilmMakinesiCloseLoadExtractor.mediaTypeForUrl(sourceUrl)
                    ) {
                        this.referer = pageUrl
                        this.headers = headersFor(pageUrl)
                    })
                    found = true
                }
                continue
            }

            val host = runCatching { URI(sourceUrl).host.orEmpty().lowercase() }.getOrDefault("")
            val closeLoadOrRapid = host.contains("closeload") ||
                host == "rapid.filmmakinesi.to" || host.startsWith("rapid.")
            if (closeLoadOrRapid) {
                var emittedByCloseLoad = false
                runCatching {
                    closeLoad.getUrl(sourceUrl, pageUrl, subtitleCallback) { link ->
                        if (!isYouTubeLink(link)) {
                            val key = mediaDedupeKey(link.url)
                            if (emittedMediaUrls.add(key)) {
                                emittedByCloseLoad = true
                                found = true
                                callback(link)
                            }
                        }
                    }
                }
                if (emittedByCloseLoad) continue
                if (host.contains("closeload")) continue
            }

            runCatching {
                val extractedLinks = mutableListOf<ExtractorLink>()
                loadExtractor(sourceUrl, pageUrl, subtitleCallback) { link ->
                    extractedLinks.add(link)
                }
                val playerLabel = when {
                    host.contains("closeload") -> "CloseLoad"
                    host == "rapid.filmmakinesi.to" || host.startsWith("rapid.") -> "Rapid"
                    else -> source.label.takeIf { it.isNotBlank() && !it.equals("Kaynak", true) } ?: "Video"
                }
                extractedLinks.forEach { link ->
                    // The site's YouTube fallbacks are unrelated to its real CloseLoad/Rapid streams.
                    if (!isYouTubeLink(link)) {
                        val key = mediaDedupeKey(link.url)
                        if (emittedMediaUrls.add(key)) {
                            callback(newExtractorLink(
                                source = "$name • $playerLabel",
                                name = "$playerLabel • ${link.name}",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer.ifBlank { pageUrl }
                                this.quality = link.quality
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                                this.audioTracks = link.audioTracks
                            })
                            found = true
                        }
                    }
                }
            }
        }
        return found
    }

    internal fun parseSourceCandidates(doc: Document, html: String, pageUrl: String): List<FilmMakinesiSource> {
        val output = linkedMapOf<String, FilmMakinesiSource>()
        val selector =
            "iframe[src], iframe[data-src], [data-video_url], [data-frame], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], " +
                "button[data-url], .partlar a[href], .parts a[href], .server-list a[href], " +
                ".servers a[href], .video-servers a[href], li[data-frame]"

        fun add(rawValue: String?, label: String, forcedPlayerElement: Boolean) {
            if (rawValue.isNullOrBlank()) return
            val cleaned = normalizeEscapedUrl(rawValue)
            val absolute = absoluteUrl(cleaned, pageUrl) ?: return
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
            val host = uri.host.lowercase()
            if (isYouTubeHost(host)) return
            val direct = isDirectMediaUrl(absolute)
            val knownPlayer = isKnownPlayerHost(host)
            val localPlayer = host.endsWith("filmmakinesi.to") &&
                (host.contains("closeload") || host.contains("rapid"))
            if (!direct && !knownPlayer && !localPlayer && !forcedPlayerElement) return
            if (host.contains("google") || host.contains("facebook") ||
                host.contains("doubleclick") || host.contains("analytics")) return
            output.putIfAbsent(absolute, FilmMakinesiSource(absolute, label.ifBlank { "Kaynak" }))
        }

        doc.select(selector).forEach { element ->
            val raw = sequenceOf(
                element.attr("data-video_url"),
                element.attr("data-frame"),
                element.attr("data-src"),
                element.attr("src"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach
            val playerList = element.closest(
                ".partlar, .parts, .server-list, .servers, .video-servers, .video-tabs, .player"
            ) != null
            val forced = element.tagName() in setOf("iframe", "video", "source", "embed") ||
                element.hasAttr("data-video_url") || element.hasAttr("data-frame") ||
                element.hasAttr("data-url") || playerList
            val label = sequenceOf(
                element.attr("data-title"),
                element.attr("title"),
                element.attr("data-name"),
                element.text()
            ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: "Kaynak"
            add(raw, label, forced)
        }

        val normalizedHtml = normalizeEscapedUrl(html)
        val urlPattern = Regex("""(?i)(?:https?:)?//[^"'\s<>]+""")
        urlPattern.findAll(normalizedHtml).forEach { match ->
            val raw = match.value.trimEnd(')', ']', '}', ',', ';')
            add(raw, "Kaynak", forcedPlayerElement = false)
        }
        return output.values.toList()
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!isSameSite(url)) return false
        val path = uri.path.orEmpty().lowercase()
        return path.startsWith("/film/") ||
            path.startsWith("/dizi/") ||
            path.startsWith("/bolum/") ||
            path.contains("/bolum-") ||
            path.startsWith("/filmler/")
    }

    private fun isSameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "filmmakinesi.to" || host.endsWith(".filmmakinesi.to")
    }

    private fun isSeriesUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/dizi/") || path.startsWith("/yabanci-dizi/")
    }

    private fun isGenericLabel(value: String): Boolean {
        val normalized = value.lowercase().trim()
        return normalized in setOf("izle", "film izle", "dizi izle", "filmmakinesi", "film makinesi")
    }

    private fun urlEpisodeLabel(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return path.substringAfterLast('/').replace('-', ' ').replace('_', ' ')
            .takeIf { it.isNotBlank() }
    }

    private fun absoluteUrl(raw: String, baseUrl: String): String? = runCatching {
        val value = Parser.unescapeEntities(normalizeEscapedUrl(raw.trim()), false)
        if (value.isBlank() || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("#")) return null
        val resolved = URI(baseUrl).resolve(URI(value)).normalize()
        if (resolved.scheme !in setOf("http", "https")) return null
        resolved.toASCIIString()
    }.getOrNull()

    private fun canonicalSiteUrl(url: String): String? {
        val absolute = absoluteUrl(url, mainUrl) ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        if (!isSameSite(absolute)) return null
        return uri.toASCIIString()
    }

    private fun headersFor(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer
    )

    private fun normalizeEscapedUrl(value: String): String = value
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")
        .trim()

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    private fun isYouTubeLink(link: ExtractorLink): Boolean =
        isYouTubeHost(runCatching { URI(link.url).host.orEmpty().lowercase() }.getOrDefault("")) ||
            link.source.contains("youtube", ignoreCase = true) ||
            link.name.contains("youtube", ignoreCase = true)

    private fun mediaDedupeKey(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, null)
            .toASCIIString()
    }.getOrDefault(url.substringBefore('#'))

    private fun isKnownPlayerHost(host: String): Boolean {
        val known = listOf(
            "closeload", "rapid", "vidmoly", "vidmoxy", "imgz.me", "streamtape",
            "filemoon", "dood", "mp4upload", "uqload", "vidhide", "streamwish",
            "upstream", "mixdrop", "fembed", "ok.ru", "dailymotion",
            "vimeo", "voe", "vidoza", "filelions", "streamsb", "mivalyo", "sendvid"
        )
        return known.any { host.contains(it) }
    }

    internal fun isDirectMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            path.endsWith(".mp4") || path.endsWith(".mkv") ||
            path.endsWith(".webm") || path.endsWith(".m4v") ||
            path.endsWith(".mov") || path.endsWith(".avi") ||
            path.endsWith(".ogv") || path.endsWith(".m3u") ||
            path.endsWith("/txt/master.txt") || path.endsWith("/master.txt")
    }

    private fun displaySourceName(label: String, url: String): String {
        val kind = FilmMakinesiCloseLoadExtractor.mediaLabelForUrl(url)
        val clean = label.replace(Regex("""\s+"""), " ").trim().take(40)
        return if (clean.isBlank() || clean.equals("Kaynak", true)) kind else "$clean • $kind"
    }
}

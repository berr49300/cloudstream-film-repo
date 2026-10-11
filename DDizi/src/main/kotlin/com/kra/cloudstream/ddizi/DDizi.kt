package com.kra.cloudstream.ddizi

import android.util.Log
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
    override var sequentialMainPage = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/yeni-eklenenler8" to "Yeni Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "$mainUrl/").document
        // Restrict parsing to episode links; scanning every navigation/ad/footer anchor
        // on each catalog page made the homepage slow and admitted irrelevant entries.
        val items = document.select("a[href*=/izle/]")
            .asSequence()
            .filter { isSameSite(it.attr("href")) }
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                anchor.toEpisodeSearchResult(href)
            }
            .distinctBy { it.url }
            .take(30)
            .toList()

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

        // Prefer the series poster over the site's generic Open Graph/logo image.
        val poster = seriesPage.selectFirst("div.afis img, img.afis, img.img-back, img.img-back-cat")
            ?.let { imageUrlFrom(it) }
            ?: seriesPage.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = seriesPage.selectFirst("meta[name=description]")?.attr("content")
            ?: seriesPage.selectFirst(".ozet, .summary, .description, [itemprop=description]")?.text()

        // Some series have multiple episode pages (base route + /sayfa-N). Fetch only
        // pagination routes underneath this exact series path, not other site sections.
        val seriesDocuments = mutableListOf(seriesPage)
        val seriesPath = runCatching { URI(seriesUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val additionalPageUrls = seriesPage.select("a[href]")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .filter { candidate ->
                if (!isSameSite(candidate)) return@filter false
                val candidatePath = runCatching { URI(candidate).path.orEmpty().trimEnd('/') }.getOrDefault("")
                candidatePath.startsWith("$seriesPath/") &&
                    Regex("""/sayfa-\d+$""").containsMatchIn(candidatePath)
            }
            .distinct()
            .sortedBy { candidate ->
                Regex("""/sayfa-(\d+)$""")
                    .find(runCatching { URI(candidate).path.orEmpty() }.getOrDefault(""))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }
            .take(20)

        additionalPageUrls.forEach { pageUrl ->
            if (pageUrl.equals(seriesUrl, ignoreCase = true)) return@forEach
            runCatching { app.get(pageUrl, referer = seriesUrl).document }
                .getOrNull()
                ?.let { seriesDocuments.add(it) }
        }

        // DDizi puts the series' own episode list before a separate "Bugünkü Bölümler"
        // list containing episodes from unrelated shows. Filter by the confirmed series
        // title instead of treating every /izle/ link on the document as a child episode.
        val episodeAnchors = seriesDocuments.flatMap { it.select("a[href]") }
            .filter { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@filter false
                if (!DDiziParser.isEpisodeUrl(href) || href.equals(seriesUrl, ignoreCase = true)) {
                    return@filter false
                }
                val label = listOf(
                    anchor.text(),
                    anchor.attr("title"),
                    anchor.selectFirst("img")?.attr("alt").orEmpty()
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                DDiziParser.belongsToSeriesEpisode(title, label)
            }
            .distinctBy { fixUrl(it.attr("href")) }

        val episodeLabels = episodeAnchors.map { anchor ->
            listOf(
                anchor.text().trim(),
                anchor.attr("title").trim(),
                anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
            ).filter { it.isNotBlank() }.joinToString(" ")
        }
        val seasonFinals = DDiziParser.seasonFinalEpisodeNumbers(episodeLabels)

        val episodes = episodeAnchors.mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val episodeTitle = listOf(
                anchor.text().trim(),
                anchor.attr("title").trim(),
                anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
            ).firstOrNull { it.isNotBlank() } ?: "Bölüm"
            val episodeNumber = DDiziParser.episodeNumber(episodeTitle)
            newEpisode(href) {
                name = episodeTitle
                episode = episodeNumber
                season = DDiziParser.seasonForEpisode(episodeNumber, seasonFinals)
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
        val document = app.get(data, referer = "$mainUrl/").document
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?, baseUrl: String = data) {
            if (raw.isNullOrBlank()) return
            val absolute = resolveCandidateUrl(baseUrl, raw.trim()) ?: return
            if (isAllowedPlayerUrl(absolute)) candidates.add(absolute)
        }

        document.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src]")
            .forEach { element ->
                listOf("src", "data-src", "data-url").forEach { attr ->
                    addCandidate(element.attr(attr))
                }
            }

        // Some DDizi pages expose the same player route through Open Graph metadata.
        document.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player]")
            .forEach { addCandidate(it.attr("content")) }

        // Part/server controls can link to a player even when the iframe has not been
        // emitted in the static markup. Exclude series and episode navigation links.
        document.select("a[href]").forEach { anchor ->
            val raw = anchor.attr("href").trim()
            val absolute = resolveCandidateUrl(data, raw) ?: return@forEach
            val label = anchor.text().trim()
            val context = (listOf(anchor.className(), anchor.id()) +
                anchor.parents().take(4).map { "${it.className()} ${it.id()}" })
                .joinToString(" ")
                .lowercase()
            val playerControl = Regex("""player|video|source|server|part|bolum|bölüm|kaynak|izle""")
                .containsMatchIn(context) ||
                Regex("""(?i)^(?:video|oynat|player|kaynak|server|source)$""").matches(label) ||
                Regex("""(?i)(?:part\s*)?[1-3](?:\.\s*)?(?:parça|parca)?""").matches(label)
            if (playerControl && isAllowedPlayerUrl(absolute) &&
                !DDiziParser.isSeriesUrl(absolute) && !DDiziParser.isEpisodeUrl(absolute)
            ) candidates.add(absolute)
        }

        // DDizi commonly uses a same-site /player/oynat/{token} wrapper, which then
        // embeds the actual player (including ddizi.re/player/youtube.php?id=...).
        // Resolve this bounded iframe chain; do not assume the wrapper itself is media.
        val queue = java.util.ArrayDeque<Pair<String, String>>()
        candidates.forEach { queue.addLast(it to data) }
        val visited = mutableSetOf<String>()
        var emitted = 0

        while (queue.isNotEmpty() && visited.size < 12) {
            val (candidate, parentReferer) = queue.removeFirst()
            if (!visited.add(candidate)) continue

            val youtubeUrl = DDiziParser.youtubeWatchUrlIfEmbed(candidate)
            if (youtubeUrl != null) {
                runCatching {
                    loadExtractor(youtubeUrl, candidate, subtitleCallback) { link ->
                        callback(link)
                        emitted++
                    }
                }
                continue
            }

            val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
            if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") || path.endsWith(".mkv")) {
                val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else null
                callback(newExtractorLink(name, name, candidate, type) {
                    referer = parentReferer
                    quality = Qualities.Unknown.value
                })
                emitted++
                continue
            }

            val playerDocument = runCatching {
                app.get(candidate, referer = parentReferer).document
            }.getOrNull()

            if (playerDocument != null) {
                val nested = linkedSetOf<String>()
                playerDocument.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src]")
                    .forEach { element ->
                        listOf("src", "data-src", "data-url").forEach { attr ->
                            val raw = element.attr(attr).trim()
                            if (raw.isNotBlank()) {
                                val absolute = resolveCandidateUrl(candidate, raw)
                                if (absolute != null && isAllowedPlayerUrl(absolute)) nested.add(absolute)
                            }
                        }
                    }
                playerDocument.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player]")
                    .forEach { meta ->
                        val absolute = resolveCandidateUrl(candidate, meta.attr("content"))
                        if (absolute != null && isAllowedPlayerUrl(absolute)) nested.add(absolute)
                    }

                // Support an explicitly declared JWPlayer literal source, but only when
                // the player page confirms that it is a JWPlayer sources configuration.
                val scripts = playerDocument.select("script").joinToString("\n") {
                    it.data().ifBlank { it.html() }
                }
                if (scripts.contains("jwplayer", ignoreCase = true) && scripts.contains("sources", ignoreCase = true)) {
                    val sourceRegex = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
                    sourceRegex.findAll(scripts).forEach { match ->
                        val absolute = resolveCandidateUrl(candidate, match.groupValues[1])
                        if (absolute != null && isAllowedPlayerUrl(absolute)) nested.add(absolute)
                    }
                }

                if (nested.isNotEmpty()) {
                    nested.forEach { queue.addLast(it to candidate) }
                    continue
                }
            }

            // Let CloudStream's registered extractors handle any player family we do
            // not parse ourselves. The source page remains the fallback referer.
            runCatching {
                loadExtractor(candidate, parentReferer, subtitleCallback) { link ->
                    callback(link)
                    emitted++
                }
            }.onFailure {
                val host = runCatching { URI(candidate).host.orEmpty() }.getOrDefault("")
                Log.d("DDizi", "No extractor emitted a link for host=$host")
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

        val poster = selectFirst("img[src], img[data-src]")?.let { imageUrlFrom(it) }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    private fun imageUrlFrom(element: Element): String? {
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
            .asSequence()
            .map { element.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            ?: return null
        return fixUrlNull(raw)
    }

    private fun isSameSite(rawUrl: String): Boolean {
        val absolute = fixUrlNull(rawUrl) ?: return false
        val host = runCatching { URI(absolute).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "ddizi.tel" || host.endsWith(".ddizi.tel")
    }

    private fun resolveCandidateUrl(baseUrl: String, rawUrl: String): String? =
        runCatching { URI(baseUrl).resolve(rawUrl).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: fixUrlNull(rawUrl)

    private fun isAllowedPlayerUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        val isDdiziOwnPlayer = host in setOf("www.ddizi.tel", "ddizi.tel") &&
            path.startsWith("/player/oynat/")
        return host.isNotBlank() &&
            (isDdiziOwnPlayer || host != "www.ddizi.tel" && host != "ddizi.tel") &&
            !host.endsWith(".google.com") && !host.contains("facebook")
    }
}

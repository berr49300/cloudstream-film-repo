package com.kra.cloudstream.ddizi

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    override var sequentialMainPage = false

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/yeni-eklenenler8" to "Yeni Eklenenler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "$mainUrl/").document
        val items = if (DDiziParser.isSeriesCatalogUrl(request.data)) {
            // The foreign-series page also contains a "today's episodes" feed. Never
            // use its /izle/ links as category items; only /diziler/ series cards belong here.
            val found = linkedMapOf<String, SearchResponse>()
            val cardSelector = "div.dizi-boxpost-cat, div.dizi-boxpost, article"
            val cards = document.select(cardSelector)
            cards.forEach { card ->
                val anchor = card.selectFirst("a[href*='/diziler/']")
                    ?: card.selectFirst("a[href]")?.takeIf {
                        DDiziParser.isSeriesUrl(fixUrlNull(it.attr("href")).orEmpty())
                    }
                    ?: return@forEach
                val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
                if (!isSameSite(href) || !DDiziParser.isSeriesUrl(href)) return@forEach
                val item = card.toSeriesSearchResult(href) ?: anchor.toSeriesSearchResult(href)
                    ?: return@forEach
                if (!isMenuOrFooterLink(anchor)) found.putIfAbsent(item.url, item)
            }
            if (found.isEmpty()) {
                document.select("a[href*='/diziler/']").forEach { anchor ->
                    val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
                    if (!isSameSite(href) || !DDiziParser.isSeriesUrl(href) ||
                        isMenuOrFooterLink(anchor)
                    ) return@forEach
                    val item = anchor.toSeriesSearchResult(href) ?: return@forEach
                    found.putIfAbsent(item.url, item)
                }
            }
            found.values.take(30)
        } else {
            // Latest/new episodes pages intentionally list episode URLs.
            document.select("a[href*=/izle/]")
                .asSequence()
                .filter { isSameSite(it.attr("href")) }
                .mapNotNull { anchor ->
                    val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                    anchor.toEpisodeSearchResult(href)
                }
                .distinctBy { it.url }
                .take(30)
                .toList()
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = DDiziParser.normalizeTitle(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()

        fun appendMatchingSeries(document: org.jsoup.nodes.Document) {
            document.select("a[href*=/diziler/]").forEach { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
                if (!isSameSite(href) || !DDiziParser.isSeriesUrl(href)) return@forEach
                val item = anchor.toSeriesSearchResult(href) ?: return@forEach
                val normalizedTitle = DDiziParser.normalizeTitle(item.name)
                val queryWords = normalizedQuery.split(' ').filter { it.isNotBlank() }
                val matches = normalizedTitle.contains(normalizedQuery) ||
                    queryWords.all { normalizedTitle.contains(it) }
                if (matches && !results.containsKey(item.url)) results[item.url] = item
            }
        }

        // Confirmed legacy site form: POST /arama/ with the "arama" field.
        runCatching {
            app.post(
                "$mainUrl/arama/",
                data = mapOf("arama" to query),
                headers = mapOf("Referer" to "$mainUrl/")
            ).document
        }.getOrNull()?.let { appendMatchingSeries(it) }

        // If the search handler changes, find matching series from the real site catalogs.
        if (results.isEmpty()) {
            for (pageUrl in listOf("$mainUrl/", "$mainUrl/yabanci-dizi-izle")) {
                val document = runCatching {
                    app.get(pageUrl, referer = "$mainUrl/").document
                }.getOrNull() ?: continue
                appendMatchingSeries(document)
            }
        }

        return results.values
            .sortedWith(compareBy<SearchResponse> { DDiziParser.normalizeTitle(it.name) != normalizedQuery }
                .thenBy { it.name.lowercase() })
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        var resolvedUrl = url
        var response = runCatching { app.get(resolvedUrl, referer = "$mainUrl/") }.getOrNull()

        if (response?.isSuccessful != true && DDiziParser.isSeriesUrl(url)) {
            // Some old DDizi cards retain a short slug while the actual live route has
            // a longer canonical slug. Recover only on a failed series request, keyed by ID.
            val id = DDiziParser.seriesId(url)
            if (!id.isNullOrBlank()) {
                val catalogUrls = listOf(
                    "$mainUrl/",
                    "$mainUrl/yabanci-dizi-izle",
                    "$mainUrl/eski.diziler.izle"
                )
                for (catalogUrl in catalogUrls) {
                    val catalog = runCatching {
                        app.get(catalogUrl, referer = "$mainUrl/").document
                    }.getOrNull() ?: continue
                    val replacement = catalog.select("a[href*='/diziler/$id/']")
                        .mapNotNull { fixUrlNull(it.attr("href")) }
                        .firstOrNull { it != url && isSameSite(it) && DDiziParser.isSeriesUrl(it) }
                        ?: continue
                    val candidateResponse = runCatching {
                        app.get(replacement, referer = "$mainUrl/")
                    }.getOrNull()
                    if (candidateResponse?.isSuccessful == true) {
                        resolvedUrl = replacement
                        response = candidateResponse
                        break
                    }
                }
            }
        }

        val successfulResponse = response?.takeIf { it.isSuccessful } ?: return null
        val page = successfulResponse.document
        val canonicalSeriesUrl = page.selectFirst("link[rel=canonical]")
            ?.attr("href")?.let { fixUrlNull(it) }
            ?.takeIf { isSameSite(it) && DDiziParser.isSeriesUrl(it) }
        if (!DDiziParser.isEpisodeUrl(resolvedUrl) && canonicalSeriesUrl != null) {
            resolvedUrl = canonicalSeriesUrl
        }

        val seriesUrl = if (DDiziParser.isEpisodeUrl(url)) {
            val episodeTitleHint = page.selectFirst("h1")?.text()?.trim()
                ?: page.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: page.title()
            page.select("a[href]").firstNotNullOfOrNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@firstNotNullOfOrNull null
                val text = anchor.text().trim()
                if (DDiziParser.isSeriesUrl(href) &&
                    (text.contains("dizi sayfas", true) || text.contains("diziye git", true) ||
                        DDiziParser.seriesUrlMatchesTitle(episodeTitleHint, href) ||
                        (anchor.parents().any { it.className().contains("breadcrumb", true) } &&
                            DDiziParser.seriesUrlMatchesTitle(episodeTitleHint, href)))
                ) href else null
            }
        } else {
            resolvedUrl
        }
        if (seriesUrl.isNullOrBlank()) return null

        val seriesPage = if (!DDiziParser.isEpisodeUrl(url)) page else
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

        // DDizi paginates historical episodes under the same series URL. /sayfa-0 is a
        // duplicate of the base page; do not let that duplicate consume the history scan.
        // All advertised pages are fetched with bounded concurrency so season-final markers
        // from older seasons are available without a long serial chain of requests.
        val seriesDocuments = mutableListOf(seriesPage)
        val seriesPath = runCatching { URI(seriesUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val additionalPageUrls = seriesPage.select("a[href]")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .filter { candidate ->
                if (!isSameSite(candidate)) return@filter false
                val candidatePath = runCatching { URI(candidate).path.orEmpty().trimEnd('/') }.getOrDefault("")
                candidatePath.startsWith("$seriesPath/") &&
                    Regex("""/sayfa-\d+$""").containsMatchIn(candidatePath) &&
                    DDiziParser.seriesPageNumber(candidate) != 0
            }
            .distinct()
            .sortedBy { candidate -> DDiziParser.seriesPageNumber(candidate) ?: Int.MAX_VALUE }
            .take(20)

        val additionalDocuments = coroutineScope {
            additionalPageUrls.chunked(4).flatMap { batch ->
                batch.map { pageUrl ->
                    async {
                        val pageStartedAt = System.currentTimeMillis()
                        val pageResponse = runCatching {
                            app.get(pageUrl, referer = seriesUrl)
                        }.getOrNull()
                        val status = pageResponse?.code ?: 0
                        Log.d(
                            "DDiziTrace",
                            "stage=episode-page route=${safeRoute(pageUrl)} http=$status elapsedMs=${System.currentTimeMillis() - pageStartedAt}"
                        )
                        pageResponse?.takeIf { it.isSuccessful }?.document
                    }
                }.awaitAll().filterNotNull()
            }
        }
        seriesDocuments.addAll(additionalDocuments)
        Log.d(
            "DDiziTrace",
            "stage=episode-pages title=${DDiziParser.normalizeTitle(title)} pageCount=${seriesDocuments.size} paginationLinks=${additionalPageUrls.size}"
        )

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
                    anchor.text().trim(),
                    anchor.attr("title").trim(),
                    anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
                ).filter { it.isNotBlank() }.joinToString(" ")
                DDiziParser.belongsToSeriesEpisode(title, label, href)
            }
            .distinctBy { fixUrl(it.attr("href")) }

        val episodeLabels = episodeAnchors.map { anchor ->
            val href = fixUrlNull(anchor.attr("href")).orEmpty()
            val label = listOf(
                anchor.text().trim(),
                anchor.attr("title").trim(),
                anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
            ).filter { it.isNotBlank() }.joinToString(" ")
            "$label ${href.substringAfterLast('/').replace('-', ' ')}"
        }
        val seasonFinals = DDiziParser.seasonFinalEpisodeNumbers(episodeLabels)

        val episodes = episodeAnchors.mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val label = listOf(
                anchor.text().trim(),
                anchor.attr("title").trim(),
                anchor.selectFirst("img")?.attr("alt").orEmpty().trim()
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            val slugText = href.substringAfterLast('/').replace('-', ' ')
            val episodeMetadata = "$label $slugText"
            val episodeNumber = DDiziParser.episodeNumber(label)
                ?: DDiziParser.episodeNumber(slugText)
                ?: DDiziParser.episodeNumber(episodeMetadata)
            val episodeSeason = DDiziParser.seasonNumber(label)
                ?: DDiziParser.seasonNumber(slugText)
            val displayTitle = label.ifBlank {
                episodeNumber?.let { "$it. Bölüm" } ?: "Bölüm"
            }
            newEpisode(href) {
                name = displayTitle
                season = episodeSeason ?: DDiziParser.seasonForEpisode(episodeNumber, seasonFinals)
                episode = if (episodeSeason != null) episodeNumber
                    else DDiziParser.seasonLocalEpisodeNumber(episodeNumber, seasonFinals)
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name.orEmpty() })

        val seasonCounts = episodes.groupingBy { it.season ?: 0 }.eachCount().toSortedMap()
        Log.i(
            "DDiziTrace",
            "stage=episode-map title=${DDiziParser.normalizeTitle(title)} pages=${seriesDocuments.size} " +
                "anchors=${episodeAnchors.size} episodes=${episodes.size} " +
                "seasonFinals=${seasonFinals.sorted()} seasonCounts=$seasonCounts"
        )
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot?.trim()
            this.tags = seriesPage.select("a[rel=tag], .genre a, .tur a")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        }
    }

    private fun decodePlayerText(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\x2f", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("\\u003d", "=", ignoreCase = true)
        .replace("\\x3d", "=", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\x3a", ":", ignoreCase = true)

    private fun isDirectMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return listOf(".m3u8", ".mp4", ".m4v", ".webm", ".mpd", ".mkv").any { path.endsWith(it) }
    }

    private fun isPlaylistEndpoint(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".m3u8") ||
            Regex("""(?i)/(?:master|playlist|index)\.txt$""").containsMatchIn(path) ||
            (path.contains("/hls/") && path.endsWith(".txt"))
    }

    /** Log only host/path; query parameters can contain signed or private playback tokens. */
    private fun safeRoute(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri.path.orEmpty().take(120)
        (host + path).ifBlank { "<unknown-route>" }
    }.getOrDefault("<invalid-route>")

    private fun sourceFamily(url: String, type: ExtractorLinkType? = null): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "youtu.be" || host.endsWith(".youtube.com") || host == "youtube.com" ||
                host.endsWith(".youtube-nocookie.com") || host == "youtube-nocookie.com" -> "YouTube"
            path.endsWith(".m3u8") || type == ExtractorLinkType.M3U8 -> "HLS"
            path.endsWith(".mpd") || type == ExtractorLinkType.DASH -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") || type == ExtractorLinkType.VIDEO -> "Video"
            else -> host.removePrefix("www.").ifBlank { "external" }
        }
    }

    private fun sourceDisplayName(url: String, type: ExtractorLinkType): String {
        val family = sourceFamily(url, type)
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return when (family) {
            "YouTube" -> "$name YouTube"
            "HLS", "DASH", "Video" -> if (host.isBlank()) "$name $family" else "$name $family ($host)"
            else -> "$name $family"
        }
    }

    private fun collectPlayerCandidates(
        document: org.jsoup.nodes.Document,
        html: String,
        baseUrl: String
    ): LinkedHashSet<String> {
        val output = linkedSetOf<String>()
        fun add(raw: String?) {
            if (raw.isNullOrBlank() || output.size >= 18) return
            val normalized = decodePlayerText(raw.trim()).trim('"', '\'', ' ', '\n', '\r')
            val url = resolveCandidateUrl(baseUrl, normalized) ?: return
            val uri = runCatching { URI(url) }.getOrNull() ?: return
            val path = uri.path.orEmpty().lowercase()
            if (!isAllowedPlayerUrl(url)) return
            if (Regex("""(?i)\.(?:css|png|jpe?g|gif|webp|svg|woff2?|ttf|ico)$""").containsMatchIn(path)) return
            if (url.length > 2500) return
            output.add(url)
        }

        val attrs = listOf("src", "data-src", "data-url", "data-embed", "data-iframe", "data-video", "data-player", "file", "data-file")
        document.select("iframe, video, source, embed, [data-embed], [data-iframe], [data-video], [data-player], [data-file], [file]")
            .forEach { element -> attrs.forEach { attr -> if (element.hasAttr(attr)) add(element.attr(attr)) } }
        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[property=og:video:iframe], meta[name=twitter:player]")
            .forEach { add(it.attr("content")) }

        // Only player controls or known player routes are treated as links, not series/episode navigation.
        document.select("a[href]").forEach { anchor ->
            val href = resolveCandidateUrl(baseUrl, anchor.attr("href").trim()) ?: return@forEach
            val path = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
            val label = anchor.text().trim()
            val playerControl = path.contains("/player/") || path.contains("/embed/") || path.contains("/video/") ||
                Regex("""(?i)^(?:video|oynat|player|kaynak|server|source|tek\s*par[cç]a|watch)$""").matches(label) ||
                Regex("""(?i)(?:part\s*)?[1-4](?:\.\s*)?(?:parça|parca|part)""").containsMatchIn(label) ||
                Regex("""(?i)player|video|source|server|kaynak|embed|oynatıcı|oynatici""")
                    .containsMatchIn(anchor.className() + " " + anchor.id())
            if (playerControl && !DDiziParser.isSeriesUrl(href) && !DDiziParser.isEpisodeUrl(href)) add(href)
        }

        val scriptText = document.select("script").joinToString("\n") { it.data().ifBlank { it.html() } }
        val variants = linkedSetOf(decodePlayerText(html), decodePlayerText(scriptText))
        val base64Pattern = Regex("""(?i)(?:atob|base64_decode|base64)\s*\(\s*["']([A-Za-z0-9+/]{20,}={0,2})["']\s*\)""")
        for (match in base64Pattern.findAll(scriptText).take(10)) {
            runCatching {
                String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { variants.add(decodePlayerText(it)) }
        }

        val directPattern = Regex("""(?i)(?:https?:)?//[^\s"'<>]+?\.(?:m3u8|mp4|m4v|webm|mpd|mkv|m3u|txt)(?:\?[^\s"'<>]*)?""")
        val attrPattern = Regex("""(?i)\b(?:src|file|fileurl|file_url|iframe|embed|video|source|stream|streamurl|stream_url|url|link|hls|playlist|contenturl|content_url|master|media|m3u8|mp4)\s*["']?\s*[:=]\s*["']([^"'<>]{5,2500})["']""")
        for (variant in variants) {
            directPattern.findAll(variant).forEach { add(it.value) }
            attrPattern.findAll(variant).forEach { match ->
                val raw = match.groupValues[1]
                val candidate = resolveCandidateUrl(baseUrl, decodePlayerText(raw)) ?: return@forEach
                val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
                val host = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")
                val isYoutube = host.contains("youtube.com") || host == "youtu.be"
                if (isDirectMediaUrl(candidate) || isPlaylistEndpoint(candidate) || isYoutube ||
                    path.contains("/player/") || path.contains("/embed/") || path.contains("/video/") ||
                    path.endsWith(".php") || path.endsWith(".js") || path.endsWith("/ajax.php") || path.endsWith("/geox.php")
                ) add(candidate)
            }
        }
        return output
    }

    private fun preferredAudioTracks(link: ExtractorLink): List<AudioFile> =
        link.audioTracks
            .sortedBy { DDiziParser.youtubeAudioTrackPriority(it.url) }
            .take(1)

    private fun preferredExternalLinks(links: List<ExtractorLink>, limit: Int = 2): List<ExtractorLink> {
        val unique = links.distinctBy { it.url }
        if (unique.isEmpty()) return emptyList()

        val qualityLinks = unique.filter { it.quality in 1..4320 }
        if (qualityLinks.isEmpty()) return unique.take(limit)

        val atOrBelow1080 = qualityLinks.filter { it.quality <= 1080 }
        val ordered = if (atOrBelow1080.isNotEmpty()) {
            atOrBelow1080.sortedWith(
                compareByDescending<ExtractorLink> { it.quality }
                    .thenBy { if (it.name.contains("h264", true) || it.name.contains("avc", true)) 0 else 1 }
            )
        } else {
            // If a host only exposes higher resolutions, use the lowest available first.
            qualityLinks.sortedWith(
                compareBy<ExtractorLink> { it.quality }
                    .thenBy { if (it.name.contains("h264", true) || it.name.contains("avc", true)) 0 else 1 }
            )
        }
        return ordered.distinctBy { it.url }.take(limit)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val response = runCatching { app.get(data, referer = "$mainUrl/") }.getOrNull() ?: return false
        if (!response.isSuccessful) {
            Log.w("DDizi", "Episode page returned HTTP " + response.code)
            return false
        }
        val candidates = linkedMapOf<String, String>()
        val partPages = linkedSetOf<String>()
        var candidateRequests = 0

        fun scanPage(doc: org.jsoup.nodes.Document, body: String, pageUrl: String) {
            collectPlayerCandidates(doc, body, pageUrl).forEach { candidate ->
                if (!candidates.containsKey(candidate)) candidates[candidate] = pageUrl
            }
            // Multi-part DDizi episodes use /2 and /3 pages. Keep this bounded.
            doc.select("a[href]").forEach { anchor ->
                val label = anchor.text().trim()
                if (!Regex("""(?i)^(?:[1-4]\s*\.?\s*)?(?:parça|parca|part)\s*[1-4]?$""").matches(label)) return@forEach
                val next = resolveCandidateUrl(pageUrl, anchor.attr("href")) ?: return@forEach
                val nextPath = runCatching { URI(next).path.orEmpty().trimEnd('/') }.getOrDefault("")
                val currentPath = runCatching { URI(data).path.orEmpty().trimEnd('/') }.getOrDefault("")
                if (next != pageUrl && nextPath.startsWith(currentPath + "/") &&
                    nextPath.substringAfterLast('/').toIntOrNull() in 2..4
                ) {
                    partPages.add(next)
                }
            }
        }

        scanPage(response.document, response.text, data)
        Log.d(
            "DDiziTrace",
            "stage=source-discovery route=${safeRoute(data)} http=${response.code} " +
                "candidateCount=${candidates.size} partCount=${partPages.size}"
        )
        // At most two extra parts; the main source is always inspected first.
        for (partUrl in partPages.take(2)) {
            val partStartedAt = System.currentTimeMillis()
            val partResponse = runCatching { app.get(partUrl, referer = data) }.getOrNull()
            candidateRequests++
            Log.d(
                "DDiziTrace",
                "stage=episode-part route=${safeRoute(partUrl)} http=${partResponse?.code ?: 0} " +
                    "elapsedMs=${System.currentTimeMillis() - partStartedAt}"
            )
            if (partResponse?.isSuccessful == true) scanPage(partResponse.document, partResponse.text, partUrl)
        }

        fun priority(url: String): Int {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            when {
                path.endsWith(".m3u8") || isDirectMediaUrl(url) -> return 0
                DDiziParser.youtubeWatchUrlIfEmbed(url) != null -> return 1
                path.endsWith("/player/telif/index.php") || path.endsWith("/youtube.php") -> return 1
                path.startsWith("/player/oynat/") || path.contains("/embed/") -> return 2
                path.endsWith("/ajax.php") || path.endsWith("/geox.php") -> return 3
                path.endsWith(".js") || path.endsWith(".php") -> return 5
            }
            return 4
        }

        val queue = java.util.ArrayDeque<Pair<String, String>>()
        candidates.entries
            .sortedBy { priority(it.key) }
            .forEach { (url, referer) -> queue.addLast(url to referer) }

        val visited = linkedSetOf<String>()
        val triedYoutube = linkedSetOf<String>()
        val emittedUrls = linkedSetOf<String>()
        val emittedByFamily = linkedMapOf<String, Int>()
        var emitted = 0

        suspend fun emitResolvedLink(
            link: ExtractorLink,
            displayName: String? = null,
            audioTracks: List<AudioFile> = link.audioTracks,
            qualityOverride: Int? = null
        ) {
            if (emitted >= 6 || !emittedUrls.add(link.url)) return
            val quality = (qualityOverride ?: link.quality).takeIf { it in 1..4320 }
                ?: Qualities.Unknown.value
            // CloudStream renders the quality field separately; including "720p" in the
            // name produced duplicate labels such as "DDizi 720p 720p".
            val label = displayName ?: if (link.type == ExtractorLinkType.M3U8) "$name HLS" else name
            // Normalize the source label across YouTube and third-party extractors. Keep the
            // original URL, headers, subtitles and audio URL; only expose the chosen track.
            callback(newExtractorLink(name, label, link.url, link.type) {
                this.referer = link.referer
                this.headers = link.headers
                this.quality = quality
                this.extractorData = link.extractorData
                this.audioTracks = audioTracks
            })
            val family = sourceFamily(link.url, link.type)
            emittedByFamily[family] = (emittedByFamily[family] ?: 0) + 1
            Log.d(
                "DDiziTrace",
                "stage=emit family=$family route=${safeRoute(link.url)} quality=$quality label=$label"
            )
            emitted++
        }

        suspend fun emitMedia(url: String, referer: String) {
            if (emitted >= 6 || !emittedUrls.add(url)) return
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            val mediaType = when {
                path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                path.endsWith(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            val quality = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val label = sourceDisplayName(url, mediaType)
            callback(newExtractorLink(
                name,
                label,
                url,
                mediaType
            ) {
                this.referer = referer
                this.headers = mapOf("Referer" to referer)
                this.quality = quality
            })
            val family = sourceFamily(url, mediaType)
            emittedByFamily[family] = (emittedByFamily[family] ?: 0) + 1
            Log.d(
                "DDiziTrace",
                "stage=emit-unprobed family=$family route=${safeRoute(url)} quality=$quality label=$label"
            )
            emitted++
        }

        while (queue.isNotEmpty() && visited.size < 12 && emitted < 6) {
            val (candidate, parentReferer) = queue.removeFirst()
            if (!visited.add(candidate)) continue
            val candidateStartedAt = System.currentTimeMillis()
            Log.d(
                "DDiziTrace",
                "stage=candidate-attempt index=${visited.size} family=${sourceFamily(candidate)} " +
                    "route=${safeRoute(candidate)} queueRemaining=${queue.size}"
            )

            val youtubeUrl = DDiziParser.youtubeWatchUrlIfEmbed(candidate)
            if (youtubeUrl != null) {
                // The same video can appear as watch, embed and relay URLs. Resolve each ID once.
                val videoId = youtubeUrl.substringAfter("v=", "").substringBefore('&')
                if (videoId.isNotBlank() && !triedYoutube.add(videoId)) continue

                val extracted = mutableListOf<ExtractorLink>()
                runCatching {
                    // Keep the 1080p option when available plus one lower-quality fallback.
                    // Audio alternatives remain bounded to one preferred default track per link.
                    loadExtractor(youtubeUrl, "", subtitleCallback) { extracted.add(it) }
                }.onFailure { Log.w("DDizi", "YouTube extraction failed for a player candidate") }

                val variants = extracted.filter {
                    it.quality > 0 || it.name.contains("youtube", ignoreCase = true)
                }.ifEmpty { extracted }
                val variantQualities = variants.map { link ->
                    link.name to (link.quality.takeIf { it in 1..4320 }
                        ?: DDiziParser.qualityFromLabel(link.name) ?: 0)
                }
                val chosenIndices = DDiziParser.preferredYoutubeVariantIndices(variantQualities, maxVariants = 2)
                Log.d(
                    "DDiziTrace",
                    "stage=youtube-extract route=${safeRoute(youtubeUrl)} elapsedMs=${System.currentTimeMillis() - candidateStartedAt} " +
                        "links=${extracted.size} qualities=${variantQualities.map { it.second }} selected=${chosenIndices.mapNotNull { variantQualities.getOrNull(it)?.second }}"
                )
                if (chosenIndices.isNotEmpty()) {
                    chosenIndices.forEach { index ->
                        val chosen = variants.getOrNull(index) ?: return@forEach
                        emitResolvedLink(
                            chosen,
                            displayName = "$name YouTube",
                            audioTracks = preferredAudioTracks(chosen),
                            qualityOverride = variantQualities[index].second
                        )
                    }
                    continue
                }

                // A direct YouTube URL has nothing useful left to parse if its extractor
                // fails, but a DDizi relay may still contain another player/source fallback.
                val candidateHost = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")
                if (candidateHost.contains("youtube") || candidateHost == "youtu.be") continue
            }

            val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
            // Do not probe a direct manifest before playback. Probing it adds latency and can
            // fail on signed/CDN URLs even though CloudStream can play the URL itself.
            if (path.endsWith(".m3u8") || isDirectMediaUrl(candidate)) {
                emitMedia(candidate, parentReferer)
                Log.d(
                    "DDiziTrace",
                    "stage=direct-media outcome=emitted-unprobed route=${safeRoute(candidate)} elapsedMs=${System.currentTimeMillis() - candidateStartedAt}"
                )
                continue
            }

            // DDizi's TXT endpoints sometimes wrap an HLS manifest; only these wrappers need
            // a network probe before they can be offered as playable M3U8 links.
            if (isPlaylistEndpoint(candidate)) {
                val playlistResponse = runCatching {
                    app.get(candidate, referer = parentReferer)
                }.getOrNull()
                candidateRequests++
                Log.d(
                    "DDiziTrace",
                    "stage=playlist-probe route=${safeRoute(candidate)} http=${playlistResponse?.code ?: 0} " +
                        "elapsedMs=${System.currentTimeMillis() - candidateStartedAt}"
                )
                val body = playlistResponse?.text.orEmpty().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                if (playlistResponse?.isSuccessful == true && body.startsWith("#EXTM3U")) {
                    emitMedia(candidate, parentReferer)
                    continue
                }
                if (playlistResponse?.isSuccessful == true) {
                    collectPlayerCandidates(playlistResponse.document, body, candidate).forEach { nested ->
                        if (!visited.contains(nested)) queue.addLast(nested to candidate)
                    }
                }
                continue
            }

            val host = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")
            val isDdiziHost = DDiziParser.isDdiziHost(candidate)
            var extractorAttempted = false

            // Try a registered extractor before downloading the same external iframe page a
            // second time. Skip bare config scripts; their contents are parsed below instead.
            val isConfigScript = path.endsWith(".js") ||
                (path.endsWith(".php") && !path.contains("/player/") &&
                    !path.endsWith("/ajax.php") && !path.endsWith("/geox.php"))
            if (!isDdiziHost && !isConfigScript) {
                extractorAttempted = true
                val extracted = mutableListOf<ExtractorLink>()
                val extractorStartedAt = System.currentTimeMillis()
                runCatching {
                    loadExtractor(candidate, parentReferer, subtitleCallback) { extracted.add(it) }
                }.onFailure { Log.d("DDizi", "Extractor failed for host=" + host, it) }
                Log.d(
                    "DDiziTrace",
                    "stage=extractor route=${safeRoute(candidate)} host=$host elapsedMs=${System.currentTimeMillis() - extractorStartedAt} " +
                        "links=${extracted.size} qualities=${extracted.map { it.quality }}"
                )
                val beforeExtractedLinks = emitted
                preferredExternalLinks(extracted).forEach { link ->
                    emitResolvedLink(link, displayName = sourceDisplayName(link.url, link.type))
                }
                if (emitted > beforeExtractedLinks) continue
            }

            val nestedStartedAt = System.currentTimeMillis()
            val nestedResponse = runCatching {
                app.get(candidate, referer = parentReferer)
            }.getOrNull()
            candidateRequests++
            if (nestedResponse?.isSuccessful == true) {
                val nested = collectPlayerCandidates(nestedResponse.document, nestedResponse.text, candidate)
                nested.forEach { next ->
                    if (!visited.contains(next)) queue.addLast(next to candidate)
                }
                Log.d(
                    "DDiziTrace",
                    "stage=nested-page route=${safeRoute(candidate)} http=${nestedResponse.code} " +
                        "elapsedMs=${System.currentTimeMillis() - nestedStartedAt} nextCandidates=${nested.size}"
                )
            } else {
                Log.d(
                    "DDiziTrace",
                    "stage=nested-page route=${safeRoute(candidate)} http=${nestedResponse?.code ?: 0} " +
                        "elapsedMs=${System.currentTimeMillis() - nestedStartedAt} nextCandidates=0"
                )
            }

            if (!isDdiziHost && !extractorAttempted) {
                val extracted = mutableListOf<ExtractorLink>()
                runCatching {
                    loadExtractor(candidate, parentReferer, subtitleCallback) { extracted.add(it) }
                }.onFailure { Log.d("DDizi", "Extractor failed for host=" + host, it) }
                preferredExternalLinks(extracted).forEach { emitResolvedLink(it) }
            }
        }

        Log.d(
            "DDiziTrace",
            "stage=source-summary elapsedMs=${System.currentTimeMillis() - startedAt} " +
                "candidates=${candidates.size} requests=$candidateRequests visited=${visited.size} " +
                "emitted=$emitted emittedByFamily=$emittedByFamily"
        )
        if (emitted == 0) Log.w(
            "DDizi",
            "No playable source; candidates=" + candidates.size + ", parts=" + partPages.size
        )
        return emitted > 0
    }

    private fun Element.toSeriesSearchResult(href: String): SearchResponse? {
        val image = selectFirst("img")
        val title = listOf(
            selectFirst("h2, h3, h4, [class*=title], [class*=baslik]")?.text(),
            image?.attr("alt")?.ifBlank { image.attr("title") },
            attr("title"),
            text()
        ).map { DDiziParser.cleanTitle(it.orEmpty().trim()) }
            .firstOrNull { it.isNotBlank() && !it.equals("iframe", true) }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = image?.let { imageUrlFrom(it) }
        }
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
        val raw = listOf(
            "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-lazy", "data-image", "data-poster", "data-srcset", "srcset", "src"
        ).asSequence()
            .map { element.attr(it).trim() }
            .filter { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            .map { value ->
                if (value.contains(",")) value.substringBefore(",").trim().substringBefore(" ").trim()
                else if (value.contains(" ")) value.substringBefore(" ").trim()
                else value
            }
            .firstOrNull()
            ?: return null
        return fixUrlNull(raw)
    }

    private fun isMenuOrFooterLink(element: Element): Boolean =
        element.parents().take(9).any { parent ->
            parent.tagName().lowercase() in setOf("nav", "header", "footer", "aside") ||
                Regex("""(?i)(menu|sidebar|side-bar|widget|navbar|navigation|footer|header|leftbar)""")
                    .containsMatchIn(parent.id() + " " + parent.className())
        }

    private fun isSameSite(rawUrl: String): Boolean {
        val absolute = fixUrlNull(rawUrl) ?: return false
        val host = runCatching { URI(absolute).host.orEmpty().lowercase() }.getOrDefault("")
        return DDiziParser.isDdiziHost(absolute)
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
        if (host.isBlank() || host.contains("facebook") || host.endsWith(".google.com") ||
            host.contains("doubleclick") || host.contains("googletagmanager") ||
            host.contains("google-analytics")
        ) return false

        val isDdiziHost = DDiziParser.isDdiziHost(url)
        if (!isDdiziHost) return true

        // Same-site sources can be served through PHP/JS/player endpoints, not only /player/oynat.
        return path.startsWith("/player/") || path.startsWith("/embed/") ||
            path.startsWith("/video/") || path == "/ajax.php" || path == "/geox.php" ||
            path.endsWith(".php") || path.endsWith(".js") || isDirectMediaUrl(url) ||
            isPlaylistEndpoint(url)
    }
}

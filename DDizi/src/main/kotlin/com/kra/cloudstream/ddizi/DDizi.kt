package com.kra.cloudstream.ddizi

import android.util.Log
import android.util.Base64
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
        val page = app.get(url, referer = "$mainUrl/").document

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
            url
        }
        if (seriesUrl.isNullOrBlank()) return null

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

        // Keep page requests bounded and avoid requiring coroutine dependencies in this plugin.
        // Most series fit in the first page; only fetch the extra pages advertised by DDizi.
        additionalPageUrls.take(8).forEach { pageUrl ->
            if (pageUrl.equals(seriesUrl, ignoreCase = true)) return@forEach
            val document = runCatching { app.get(pageUrl, referer = seriesUrl).document }.getOrNull()
            if (document != null) seriesDocuments.add(document)
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
        return ordered.distinctBy { it.quality }.take(limit)
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
        // At most two extra parts; the main source is always inspected first.
        for (partUrl in partPages.take(2)) {
            val partResponse = runCatching { app.get(partUrl, referer = data) }.getOrNull() ?: continue
            candidateRequests++
            if (partResponse.isSuccessful) scanPage(partResponse.document, partResponse.text, partUrl)
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
            callback(newExtractorLink(
                name,
                if (mediaType == ExtractorLinkType.M3U8) "$name HLS" else name,
                url,
                mediaType
            ) {
                this.referer = referer
                this.headers = mapOf("Referer" to referer)
                this.quality = quality
            })
            emitted++
        }

        while (queue.isNotEmpty() && visited.size < 12 && emitted < 6) {
            val (candidate, parentReferer) = queue.removeFirst()
            if (!visited.add(candidate)) continue

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
                if (chosenIndices.isNotEmpty()) {
                    chosenIndices.forEach { index ->
                        val chosen = variants.getOrNull(index) ?: return@forEach
                        emitResolvedLink(
                            chosen,
                            displayName = name,
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
                continue
            }

            // DDizi's TXT endpoints sometimes wrap an HLS manifest; only these wrappers need
            // a network probe before they can be offered as playable M3U8 links.
            if (isPlaylistEndpoint(candidate)) {
                val playlistResponse = runCatching {
                    app.get(candidate, referer = parentReferer)
                }.getOrNull()
                candidateRequests++
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
                runCatching {
                    loadExtractor(candidate, parentReferer, subtitleCallback) { extracted.add(it) }
                }.onFailure { Log.d("DDizi", "Extractor failed for host=" + host, it) }
                val beforeExtractedLinks = emitted
                preferredExternalLinks(extracted).forEach { emitResolvedLink(it) }
                if (emitted > beforeExtractedLinks) continue
            }

            val nestedResponse = runCatching {
                app.get(candidate, referer = parentReferer)
            }.getOrNull()
            candidateRequests++
            if (nestedResponse?.isSuccessful == true) {
                val nested = collectPlayerCandidates(nestedResponse.document, nestedResponse.text, candidate)
                nested.forEach { next ->
                    if (!visited.contains(next)) queue.addLast(next to candidate)
                }
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
            "DDizi",
            "Source scan elapsedMs=" + (System.currentTimeMillis() - startedAt) +
                " candidates=" + candidates.size + " requests=" + candidateRequests +
                " visited=" + visited.size + " emitted=" + emitted
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

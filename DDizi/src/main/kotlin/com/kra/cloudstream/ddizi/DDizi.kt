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

    private fun decodePlayerText(value: String): String = value\n        .replace("&amp;", "&")\n        .replace("&#038;", "&")\n        .replace("\\/", "/")\n        .replace("\\u002f", "/", ignoreCase = true)\n        .replace("\\x2f", "/", ignoreCase = true)\n        .replace("\\u0026", "&", ignoreCase = true)\n        .replace("\\x26", "&", ignoreCase = true)\n        .replace("\\u003d", "=", ignoreCase = true)\n        .replace("\\x3d", "=", ignoreCase = true)\n        .replace("\\u003a", ":", ignoreCase = true)\n        .replace("\\x3a", ":", ignoreCase = true)\n\n    private fun isDirectMediaUrl(url: String): Boolean {\n        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")\n        return listOf(".m3u8", ".mp4", ".m4v", ".webm", ".mpd", ".mkv").any { path.endsWith(it) }\n    }\n\n    private fun isPlaylistEndpoint(url: String): Boolean {\n        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")\n        return path.endsWith(".m3u8") ||\n            Regex("""(?i)/(?:master|playlist|index)\\.txt$""").containsMatchIn(path) ||\n            (path.contains("/hls/") && path.endsWith(".txt"))\n    }\n\n    private fun collectPlayerCandidates(\n        document: org.jsoup.nodes.Document,\n        html: String,\n        baseUrl: String\n    ): LinkedHashSet<String> {\n        val output = linkedSetOf<String>()\n        fun add(raw: String?) {\n            if (raw.isNullOrBlank() || output.size >= 36) return\n            val normalized = decodePlayerText(raw.trim()).trim('"', '\'', ' ', '\\n', '\\r')\n            val url = resolveCandidateUrl(baseUrl, normalized) ?: return\n            val uri = runCatching { URI(url) }.getOrNull() ?: return\n            val path = uri.path.orEmpty().lowercase()\n            if (!isAllowedPlayerUrl(url)) return\n            if (Regex("""(?i)\\.(?:css|png|jpe?g|gif|webp|svg|woff2?|ttf|ico)(?:$|/)""").containsMatchIn(path)) return\n            if (url.length > 2500) return\n            output.add(url)\n        }\n\n        val attrs = listOf("src", "data-src", "data-url", "data-embed", "data-iframe", "data-video", "data-player", "file", "data-file")\n        document.select("iframe, video, source, embed, [data-embed], [data-iframe], [data-video], [data-player], [data-file], [file]")\n            .forEach { element -> attrs.forEach { attr -> if (element.hasAttr(attr)) add(element.attr(attr)) } }\n        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[property=og:video:iframe], meta[name=twitter:player]")\n            .forEach { add(it.attr("content")) }\n\n        // Only player controls or known player routes are treated as links, not series/episode navigation.\n        document.select("a[href]").forEach { anchor ->\n            val href = resolveCandidateUrl(baseUrl, anchor.attr("href").trim()) ?: return@forEach\n            val path = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")\n            val label = anchor.text().trim()\n            val playerControl = path.contains("/player/") || path.contains("/embed/") || path.contains("/video/") ||\n                Regex("""(?i)^(?:video|oynat|player|kaynak|server|source|tek\\s*par[cç]a|watch)$""").matches(label) ||\n                Regex("""(?i)(?:part\\s*)?[1-4](?:\\.\\s*)?(?:parça|parca|part)""").containsMatchIn(label) ||\n                Regex("""(?i)player|video|source|server|kaynak|embed|oynatıcı|oynatici""")\n                    .containsMatchIn(anchor.className() + " " + anchor.id())\n            if (playerControl && !DDiziParser.isSeriesUrl(href) && !DDiziParser.isEpisodeUrl(href)) add(href)\n        }\n\n        val scriptText = document.select("script").joinToString("\\n") { it.data().ifBlank { it.html() } }\n        val variants = linkedSetOf(decodePlayerText(html), decodePlayerText(scriptText))\n        val base64Pattern = Regex("""(?i)(?:atob|base64_decode|base64)\\s*\\(\\s*["']([A-Za-z0-9+/]{20,}={0,2})["']\\s*\\)""")\n        for (match in base64Pattern.findAll(scriptText).take(24)) {\n            runCatching {\n                String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)\n            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { variants.add(decodePlayerText(it)) }\n        }\n\n        val directPattern = Regex("""(?i)(?:https?:)?//[^"'<>\\\\\\s]+?\\.(?:m3u8|mp4|m4v|webm|mpd|mkv|m3u|txt)(?:\\?[^"'<>\\\\\\s]*)?""")\n        val attrPattern = Regex("""(?i)\\b(?:src|file|fileurl|file_url|iframe|embed|video|source|stream|streamurl|stream_url|url|link|hls|playlist|contenturl|content_url|master|media|m3u8|mp4)\\s*["']?\\s*[:=]\\s*["']([^"'<>]{5,2500})["']""")\n        for (variant in variants) {\n            directPattern.findAll(variant).forEach { add(it.value) }\n            attrPattern.findAll(variant).forEach { match ->\n                val raw = match.groupValues[1]\n                val candidate = resolveCandidateUrl(baseUrl, decodePlayerText(raw)) ?: return@forEach\n                val path = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")\n                val host = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")\n                val isYoutube = host.contains("youtube.com") || host == "youtu.be"\n                if (isDirectMediaUrl(candidate) || isPlaylistEndpoint(candidate) || isYoutube ||\n                    path.contains("/player/") || path.contains("/embed/") || path.contains("/video/") ||\n                    path.endsWith(".php") || path.endsWith(".js") || path.endsWith("/ajax.php") || path.endsWith("/geox.php")\n                ) add(candidate)\n            }\n        }\n        return output\n    }\n\n    override suspend fun loadLinks(\n        data: String,\n        isCasting: Boolean,\n        subtitleCallback: (SubtitleFile) -> Unit,\n        callback: (ExtractorLink) -> Unit\n    ): Boolean {\n        val response = runCatching { app.get(data, referer = "$mainUrl/") }.getOrNull() ?: return false\n        if (!response.isSuccessful) {\n            Log.w("DDizi", "Episode page returned HTTP ${response.code}")\n            return false\n        }\n        val document = response.document\n        val candidates = linkedMapOf<String, String>()\n        val partPages = linkedSetOf<String>()\n\n        fun scanPage(doc: org.jsoup.nodes.Document, body: String, pageUrl: String) {\n            for (candidate in collectPlayerCandidates(doc, body, pageUrl)) {\n                if (!candidates.containsKey(candidate)) candidates[candidate] = pageUrl\n            }\n            // Multi-part DDizi episodes use /2, /3 ... page suffixes; inspect each part's own player.\n            doc.select("a[href]").forEach { anchor ->\n                val label = anchor.text().trim()\n                if (!Regex("""(?i)^(?:[1-4]\\s*\\.?\\s*)?(?:parça|parca|part)\\s*[1-4]?$""").matches(label)) return@forEach\n                val next = resolveCandidateUrl(pageUrl, anchor.attr("href")) ?: return@forEach\n                val nextPath = runCatching { URI(next).path.orEmpty().trimEnd('/') }.getOrDefault("")\n                val currentPath = runCatching { URI(data).path.orEmpty().trimEnd('/') }.getOrDefault("")\n                if (next != pageUrl && nextPath.startsWith(currentPath + "/") && nextPath.substringAfterLast('/').toIntOrNull() in 2..4) {\n                    partPages.add(next)\n                }\n            }\n        }\n\n        scanPage(document, response.text, data)\n        // Source links on one part may be dead while another part has an active player.\n        for (partUrl in partPages.take(4)) {\n            val partResponse = runCatching { app.get(partUrl, referer = data) }.getOrNull() ?: continue\n            if (partResponse.isSuccessful) scanPage(partResponse.document, partResponse.text, partUrl)\n        }\n\n        val queue = java.util.ArrayDeque<Pair<String, String>>()\n        candidates.forEach { (url, referer) -> queue.addLast(url to referer) }\n        val visited = linkedSetOf<String>()\n        val emittedUrls = linkedSetOf<String>()\n        var emitted = 0\n\n        fun emitMedia(url: String, referer: String, hls: Boolean) {\n            if (!emittedUrls.add(url)) return\n            val headers = mapOf("Referer" to referer)\n            callback(newExtractorLink(name, if (hls) "$name HLS" else name, url, if (hls) ExtractorLinkType.M3U8 else null) {\n                this.referer = referer\n                this.headers = headers\n                quality = Qualities.Unknown.value\n            })\n            emitted++\n        }\n\n        while (queue.isNotEmpty() && visited.size < 28 && emitted < 12) {\n            val (candidate, parentReferer) = queue.removeFirst()\n            if (!visited.add(candidate)) continue\n\n            val youtubeUrl = DDiziParser.youtubeWatchUrlIfEmbed(candidate)\n            if (youtubeUrl != null) {\n                // YouTube's extractor must not inherit the DDizi player wrapper as its Referer.\n                runCatching { loadExtractor(youtubeUrl, "", subtitleCallback) { link ->\n                    if (emittedUrls.add(link.url)) { callback(link); emitted++ }\n                } }.onFailure { Log.w("DDizi", "YouTube extractor failed", it) }\n                continue\n            }\n\n            val candidatePath = runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")\n            val isHls = isPlaylistEndpoint(candidate)\n            if (isDirectMediaUrl(candidate) && !isHls) {\n                emitMedia(candidate, parentReferer, false)\n                continue\n            }\n            if (isHls) {\n                val playlistResponse = runCatching { app.get(candidate, referer = parentReferer) }.getOrNull()\n                val body = playlistResponse?.text.orEmpty().trimStart('\\uFEFF', ' ', '\\r', '\\n', '\\t')\n                if (playlistResponse?.isSuccessful == true && body.startsWith("#EXTM3U")) {\n                    emitMedia(candidate, parentReferer, true)\n                    continue\n                }\n                if (playlistResponse?.isSuccessful == true) {\n                    collectPlayerCandidates(playlistResponse.document, body, candidate).forEach { nested ->\n                        if (!visited.contains(nested)) queue.addLast(nested to candidate)\n                    }\n                }\n                // A true .m3u8 URL is still offered as a fallback if the probe is blocked by the host.\n                if (candidatePath.endsWith(".m3u8") && playlistResponse == null) emitMedia(candidate, parentReferer, true)\n                continue\n            }\n\n            val nestedResponse = runCatching { app.get(candidate, referer = parentReferer) }.getOrNull()\n            if (nestedResponse?.isSuccessful == true) {\n                val nested = collectPlayerCandidates(nestedResponse.document, nestedResponse.text, candidate)\n                nested.forEach { next -> if (!visited.contains(next)) queue.addLast(next to candidate) }\n            }\n\n            val host = runCatching { URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")\n            val isInternalPage = host == "ddizi.tel" || host.endsWith(".ddizi.tel")\n            if (!isInternalPage || nestedResponse?.isSuccessful != true) {\n                runCatching { loadExtractor(candidate, parentReferer, subtitleCallback) { link ->\n                    if (emittedUrls.add(link.url)) { callback(link); emitted++ }\n                } }.onFailure { Log.d("DDizi", "Extractor could not resolve host=$host", it) }\n            }\n        }\n\n        if (emitted == 0) Log.w("DDizi", "No playable source for episode $data; candidates=${candidates.size}, parts=${partPages.size}")\n        return emitted > 0\n    }\n    private fun Element.toSeriesSearchResult(href: String): SearchResponse? {
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
            (path.startsWith("/player/oynat/") || path.endsWith("/player/telif/index.php"))
        return host.isNotBlank() &&
            (isDdiziOwnPlayer || host != "www.ddizi.tel" && host != "ddizi.tel") &&
            !host.endsWith(".google.com") && !host.contains("facebook")
    }
}

package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.Calendar

class SelcukFlix : MainAPI() {
    override var mainUrl = "https://selcukflix.app"
    override var name = "SelcukFlix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { jacksonObjectMapper() }

    // This is intentionally a normal same-origin API request, not a Cloudflare bypass.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to "https://selcukflix.app",
        "Referer" to "https://selcukflix.app/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle" to "Yeni Filmler",
        "$mainUrl/dizi-izle" to "Yeni Diziler"
    )

    private data class SourceItem(val content: String, val label: String)

    private fun JsonNode.field(name: String): JsonNode? {
        if (!isObject) return null
        val iterator = fields()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.equals(name, ignoreCase = true)) return entry.value
        }
        return null
    }

    private fun JsonNode.textField(vararg names: String): String? {
        for (name in names) {
            val value = field(name) ?: continue
            if (value.isNull || value.isContainerNode) continue
            val text = value.asText().trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return null
    }

    private fun JsonNode.children(): List<JsonNode> = when {
        isArray -> elements().asSequence().toList()
        isObject -> fields().asSequence().map { it.value }.toList()
        else -> emptyList()
    }

    private fun String.looksLikeJson(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun isBlocked(html: String, context: String): Boolean {
        if (!SelcukFlixResolverBank.isAccessChallenge(html)) return false
        android.util.Log.w(
            "SelcukFlix",
            "34 • SessionDependentSourceDiagnostic: BLOCKED during " + context +
                "; Cloudflare/CAPTCHA was detected. No challenge bypass was attempted."
        )
        return true
    }

    private fun canonicalSiteUrl(raw: String?): String? =
        SelcukFlixResolverBank.normalizeSiteUrl(raw, mainUrl)

    private fun responsePayload(body: String): String {
        if (SelcukFlixResolverBank.isAccessChallenge(body)) {
            throw ErrorLoadingException("SelcukFlix erişimi Cloudflare/CAPTCHA yanıtıyla engellendi; otomatik aşma yapılmadı.")
        }
        val parsed = runCatching { mapper.readTree(body) }.getOrNull() ?: return body
        val response = parsed.field("response") ?: return body
        if (response.isNull) return body
        val value = if (response.isTextual) response.asText() else response.toString()
        return SelcukFlixResolverBank.decodeSitePayload(value) ?: value
    }

    private suspend fun requestApi(path: String): JsonNode {
        val body = try {
            app.post(
                mainUrl.trimEnd('/') + path,
                headers = apiHeaders,
                referer = mainUrl.trimEnd('/') + "/"
            ).text
        } catch (error: Exception) {
            val detail = error.message.orEmpty()
            val blocked = Regex("""(?i)(403|forbidden|1020|1015|captcha|cloudflare|access denied)""")
                .containsMatchIn(detail)
            if (blocked) {
                android.util.Log.w(
                    "SelcukFlix",
                    "BLOCKED at API request; access challenge/status marker detected. No workaround attempted."
                )
                throw ErrorLoadingException("SelcukFlix API erişimi BLOCKED (HTTP/access challenge); otomatik aşma yapılmadı.")
            }
            val kind = error.javaClass.simpleName.ifBlank { "NetworkError" }
            throw ErrorLoadingException(
                "SelcukFlix /api/bg/searchcontent API isteği başarısız ($kind)."
            )
        }
        if (isBlocked(body, path)) {
            throw ErrorLoadingException("SelcukFlix şu anda Cloudflare/CAPTCHA nedeniyle BLOCKED; erişim engeli aşılmadı.")
        }
        val payload = responsePayload(body)
        return runCatching { mapper.readTree(payload) }.getOrElse {
            throw ErrorLoadingException("SelcukFlix API JSON yanıtı okunamadı; uç nokta varsayımı HYPOTHESIS durumunda.")
        }
    }

    private fun resultArray(root: JsonNode): List<JsonNode> {
        if (root.isArray) return root.elements().asSequence().toList()
        val result = root.field("result")
        if (result?.isArray == true) return result.elements().asSequence().toList()
        val nested = root.field("data")?.field("result")
        if (nested?.isArray == true) return nested.elements().asSequence().toList()
        return emptyList()
    }

    private fun normalizePoster(raw: String?): String? =
        raw?.let { canonicalSiteUrl(it) ?: SelcukFlixResolverBank.normalizeUrl(it, mainUrl) }

    private fun asSearchResponse(item: JsonNode): SearchResponse? {
        val title = item.textField(
            "object_name", "object_alternative_name", "used_title",
            "original_title", "originalTitle", "culture_title", "cultureTitle",
            "title", "name"
        ) ?: return null
        val rawUrl = item.textField("used_slug", "usedSlug", "slug", "url", "link") ?: return null
        val url = canonicalSiteUrl(rawUrl) ?: return null
        val poster = normalizePoster(item.textField(
            "object_poster_url", "object_face_url", "poster_url", "face_url",
            "posterUrl", "poster", "image", "thumbnail"
        ))
        val score = item.textField("imdb_point", "imdbPoint", "rating", "score")
        val typeText = item.textField("used_type", "type", "contentType", "itemType").orEmpty().lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        val isMovie = path.startsWith("/film/") ||
            typeText.contains("movie") || typeText == "film" ||
            (item.textField("type_id", "typeId")?.toIntOrNull() == 2 && !path.startsWith("/dizi/"))
        val isSeries = path.startsWith("/dizi/") ||
            typeText in setOf("series", "tvseries", "tv series", "dizi", "tv-series")
        return if (isSeries && !isMovie) {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = canonicalSiteUrl(request.data) ?: request.data
        val pageUrl = if (page > 1) {
            base + (if (base.contains("?")) "&" else "?") + "page=" + page
        } else {
            base
        }
        val html = loadPageHtml(pageUrl, "katalog")
        val document = Jsoup.parse(html, pageUrl)
        val result = mutableListOf<SearchResponse>()

        val nextDataText = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: document.selectFirst("script#__NEXT_DATA__")?.html()
        val nextRoot = nextDataText?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        val pageProps = nextRoot?.field("props")?.field("pageProps")
        val initialSeries = pageProps?.field("initialSeries")
        if (initialSeries != null && !initialSeries.isNull) {
            val encoded = if (initialSeries.isTextual) initialSeries.asText() else initialSeries.toString()
            val decoded = SelcukFlixResolverBank.decodeSitePayload(encoded) ?: encoded
            val items = runCatching { mapper.readTree(decoded) }.getOrNull()
            if (items?.isArray == true) {
                items.forEach { item -> asSearchResponse(item)?.let(result::add) }
            }
        }

        if (result.isEmpty()) {
            document.select(
                "a[href^='dizi/'], a[href^='/dizi/'], a[href^='film/'], a[href^='/film/'], " +
                    "a[href*='/dizi/'], a[href*='/film/']"
            ).forEach { anchor ->
                val image = anchor.selectFirst("img") ?: return@forEach
                val href = canonicalSiteUrl(anchor.attr("href")) ?: return@forEach
                val path = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
                if (path.contains("/sezon-") || path.contains("/bolum-")) return@forEach
                val title = image.attr("alt").ifBlank { anchor.attr("title") }
                    .ifBlank { anchor.text() }
                    .removeSuffix(" izle")
                    .removeSuffix(" full hd izle")
                    .trim()
                if (title.isBlank()) return@forEach
                val poster = normalizePoster(image.attr("data-src").ifBlank {
                    image.attr("data-lazy-src").ifBlank { image.attr("src") }
                })
                val response = if (path.startsWith("/film/")) {
                    newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                }
                result += response
            }
        }

        val unique = result.distinctBy { it.url }
        return newHomePageResponse(request.name, unique, hasNext = unique.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = requestApi("/api/bg/searchcontent?searchterm=" + encoded)
        val state = root.field("state")
        if (state != null && state.isBoolean && !state.asBoolean()) {
            throw ErrorLoadingException("SelcukFlix arama API'si başarısız yanıt döndürdü.")
        }
        val results = root.field("result")?.takeIf { it.isArray } ?: return emptyList()
        return results.mapNotNull { asSearchResponse(it) }
            .filterNot { it.url.contains("/seri-filmler/", true) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun loadPageHtml(url: String, context: String): String {
        val html = try {
            app.get(url, headers = browserHeaders, referer = mainUrl.trimEnd('/') + "/").text
        } catch (error: Exception) {
            val detail = error.message.orEmpty()
            val blocked = Regex("""(?i)(403|forbidden|1020|1015|captcha|cloudflare|access denied)""")
                .containsMatchIn(detail)
            if (blocked) {
                android.util.Log.w(
                    "SelcukFlix",
                    "BLOCKED at page request for " + context + "; access challenge/status marker detected. No workaround attempted."
                )
                throw ErrorLoadingException("SelcukFlix $context erişimi BLOCKED (HTTP/access challenge); otomatik aşma yapılmadı.")
            }
            val kind = error.javaClass.simpleName.ifBlank { "NetworkError" }
            throw ErrorLoadingException("SelcukFlix sayfasına erişilemedi ($kind).")
        }
        if (isBlocked(html, context)) {
            throw ErrorLoadingException("SelcukFlix $context sayfası Cloudflare/CAPTCHA nedeniyle BLOCKED.")
        }
        return html
    }

    private fun decodedPageRoot(html: String): JsonNode {
        val payload = SelcukFlixResolverBank.extractEmbeddedPayload(html)
            ?: if (html.looksLikeJson()) html else null
            ?: throw ErrorLoadingException("SelcukFlix sayfasında beklenen pageProps.secureData verisi bulunamadı.")
        return runCatching { mapper.readTree(payload) }.getOrElse {
            throw ErrorLoadingException("SelcukFlix sayfa verisi JSON olarak ayrıştırılamadı.")
        }
    }

    private fun episodesFrom(root: JsonNode): List<Episode> =
        SelcukFlixResolverBank.parseEpisodeRecords(root, mainUrl).map { record ->
            newEpisode(record.url) {
                name = record.name
                season = record.season
                episode = record.episode
                posterUrl = record.posterUrl
            }
        }

    override suspend fun load(url: String): LoadResponse {
        val canonicalUrl = canonicalSiteUrl(url)
            ?: throw ErrorLoadingException("Geçersiz SelcukFlix içerik adresi.")
        val html = loadPageHtml(canonicalUrl, "detay")
        val document = Jsoup.parse(html, canonicalUrl)

        // Prefer the visible page's metadata; decrypted hydration data is a fallback when present.
        val root = runCatching { decodedPageRoot(html) }.getOrNull()
        val item = root?.field("contentItem") ?: root?.field("ContentItem") ?: root
        val title = item?.textField("original_title", "originalTitle", "culture_title", "cultureTitle", "title", "name")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.removeSuffix(" izle")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("SelcukFlix detay sayfasında başlık bulunamadı.")

        val description = item?.textField("description", "plot", "overview", "summary")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val poster = normalizePoster(
            item?.textField("poster_url", "posterUrl", "poster", "image")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img[alt]")?.let { image ->
                    image.attr("data-src").ifBlank { image.attr("data-lazy-src").ifBlank { image.attr("src") } }
                }
        )

        val year = item?.textField("release_year", "releaseYear", "year")?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
            ?: document.selectFirst("a[href^='/yil/']")?.text()?.toIntOrNull()
        val tags = item?.textField("categories", "category", "genres")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val rating = item?.textField("imdb_point", "imdbPoint", "rating", "score")
        val duration = item?.textField("total_minutes", "totalMinutes", "duration", "runtime")?.toIntOrNull()

        val seasonLinks = document.select("a[href*='/sezon-']")
            .mapNotNull { anchor ->
                val href = canonicalSiteUrl(anchor.attr("href")) ?: return@mapNotNull null
                val path = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
                if (Regex("""/sezon-\d+$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) href else null
            }
            .distinct()

        val episodeEntries = mutableListOf<Triple<Int, Int, Episode>>()
        val seenEpisodeUrls = mutableSetOf<String>()
        for (seasonUrl in seasonLinks.take(40)) {
            val seasonHtml = try {
                loadPageHtml(seasonUrl, "sezon")
            } catch (_: Exception) {
                continue
            }
            val seasonDoc = Jsoup.parse(seasonHtml, seasonUrl)
            seasonDoc.select("a[href*='/sezon-'][href*='/bolum-']").forEach { anchor ->
                val episodeUrl = canonicalSiteUrl(anchor.attr("href")) ?: return@forEach
                if (!seenEpisodeUrls.add(episodeUrl)) return@forEach
                val uriPath = runCatching { URI(episodeUrl).path.orEmpty() }.getOrDefault("")
                val nums = Regex("""/sezon-(\d+)/bolum-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(uriPath) ?: return@forEach
                val seasonNo = nums.groupValues[1].toIntOrNull() ?: return@forEach
                val episodeNo = nums.groupValues[2].toIntOrNull() ?: return@forEach
                val episodeName = anchor.selectFirst("h2, h3, span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "$seasonNo. Sezon $episodeNo. Bölüm"
                episodeEntries += Triple(
                    seasonNo,
                    episodeNo,
                    newEpisode(episodeUrl) {
                        name = episodeName
                        season = seasonNo
                        episode = episodeNo
                    }
                )
            }
        }

        val episodes = episodeEntries
            .sortedWith(compareBy<Triple<Int, Int, Episode>> { it.first }.thenBy { it.second })
            .map { it.third }
        val path = runCatching { URI(canonicalUrl).path.orEmpty().lowercase() }.getOrDefault("")
        val isSeries = path.startsWith("/dizi/") || seasonLinks.isNotEmpty() ||
            episodes.isNotEmpty() || root?.field("relatedResults")?.field("getSerieSeasonAndEpisodes") != null

        return if (isSeries) {
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
            }
        }
    }

    private fun collectSourceItems(root: JsonNode): List<SourceItem> {
        val found = linkedMapOf<String, SourceItem>()

        fun walk(node: JsonNode) {
            if (node.isObject) {
                val content = node.textField(
                    "source_content", "sourceContent", "sourceUrl", "source_url",
                    "embedUrl", "embed_url", "videoUrl", "video_url"
                )
                if (!content.isNullOrBlank()) {
                    val quality = node.textField("quality_name", "qualityName", "name", "label")
                        ?: "Kaynak " + (found.size + 1)
                    val key = quality + "|" + content
                    found.putIfAbsent(key, SourceItem(content, quality))
                }
                node.children().forEach(::walk)
            } else if (node.isArray) {
                node.children().forEach(::walk)
            }
        }

        walk(root)
        return found.values.toList()
    }

    private suspend fun expandPichiveCandidates(
        input: List<SelcukResolverCandidate>,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<SelcukResolverCandidate> {
        val output = input.toMutableList()
        val pichiveFrames = input.filter {
            it.id == 15 && SelcukFlixResolverBank.isPichiveIframe(it.url)
        }.distinctBy { it.url }.take(8)

        for (frame in pichiveFrames) {
            val iframeHtml = try {
                // Use the normal embedding-page referer once. If the site responds with a challenge,
                // mark it BLOCKED instead of trying alternate headers, mirrors, or challenge workarounds.
                app.get(frame.url, headers = browserHeaders, referer = episodeUrl).text
            } catch (_: Exception) {
                android.util.Log.w(
                    "SelcukFlix",
                    "38 • PichiveSourceApiResolver: BLOCKED/NOT TESTED at iframe request; no retry or challenge bypass."
                )
                continue
            }
            if (isBlocked(iframeHtml, "Pichive iframe")) continue

            SelcukFlixResolverBank.extractSubtitles(iframeHtml, frame.url).forEach { subtitle ->
                subtitleCallback(
                    newSubtitleFile("35 • " + subtitle.language, subtitle.url) {
                        headers = browserHeaders + mapOf("Referer" to frame.url)
                    }
                )
            }

            val token = SelcukFlixResolverBank.extractPichiveToken(iframeHtml)
            if (token.isNullOrBlank()) {
                android.util.Log.d(
                    "SelcukFlix",
                    "38 • PichiveSourceApiResolver: NOT FOUND; openPlayer token absent in non-challenge page."
                )
                continue
            }

            val apiUrl = SelcukFlixResolverBank.pichiveSourceApiUrl(frame.url, token)
                ?: continue
            val response = try {
                app.get(
                    apiUrl,
                    headers = browserHeaders + mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = frame.url
                ).text
            } catch (_: Exception) {
                android.util.Log.w(
                    "SelcukFlix",
                    "38 • PichiveSourceApiResolver: source API request failed; request URL/token omitted."
                )
                continue
            }

            if (isBlocked(response, "Pichive source API")) continue
            val parsed = SelcukFlixResolverBank.extractPichiveSourceCandidates(response, frame.url)
            if (parsed.isEmpty()) {
                android.util.Log.d(
                    "SelcukFlix",
                    "38 • PichiveSourceApiResolver: no recognized source entries in the normal API response."
                )
            }
            output += parsed
        }

        return output.distinctBy { it.id.toString() + "|" + it.url + "|" + it.referer + "|" + it.note }
            .take(220)
    }

    private suspend fun expandNestedIframeCandidates(
        input: List<SelcukResolverCandidate>,
        episodeUrl: String
    ): List<SelcukResolverCandidate> {
        val output = input.toMutableList()
        val parents = input.filter {
            it.id == 15 && !SelcukFlixResolverBank.isPichiveIframe(it.url)
        }
            .distinctBy { it.url }
            .take(6)
        for (parent in parents) {
            val html = try {
                app.get(parent.url, referer = parent.referer.ifBlank { episodeUrl }).text
            } catch (_: Exception) {
                continue
            }
            if (isBlocked(html, "nested player")) continue
            val nested = SelcukFlixResolverBank.extractCandidates(html, parent.url, mainUrl)
                .filterNot { it.url == parent.url }
            nested.forEach { child ->
                output += child
                output += child.copy(
                    id = 16,
                    referer = parent.url,
                    note = "via " + child.id.toString().padStart(2, '0')
                )
            }
        }
        return output.distinctBy { it.id.toString() + "|" + it.url + "|" + it.referer + "|" + it.note }
            .take(180)
    }

    private fun sourceLabel(candidate: SelcukResolverCandidate, source: SourceItem): String {
        val id = candidate.id.toString().padStart(2, '0')
        val nested = candidate.note?.let { " • " + it }.orEmpty()
        return id + " • " + SelcukFlixResolverBank.resolverName(candidate.id) + nested +
            " • " + source.label.take(48)
    }

    private fun directType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.lowercase())
        return when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
                path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = canonicalSiteUrl(data) ?: data
        val html = loadPageHtml(episodeUrl, "oynatıcı/kaynak")
        val payload = SelcukFlixResolverBank.extractEmbeddedPayload(html)
        val root = if (!payload.isNullOrBlank()) {
            runCatching { mapper.readTree(payload) }.getOrNull()
        } else {
            null
        }

        val sources = root?.let(::collectSourceItems).orEmpty()
        val sourceItems = if (sources.isNotEmpty()) sources else listOf(
            SourceItem(payload ?: html, "Sayfa verisi")
        )

        var totalResolved = 0
        val manifestChecked = mutableSetOf<String>()
        val playerCache = mutableMapOf<String, List<ExtractorLink>>()
        val sourceCache = mutableMapOf<String, List<SelcukResolverCandidate>>()

        sourceItems.take(12).forEachIndexed { sourceIndex, source ->
            SelcukFlixResolverBank.extractSubtitles(source.content, episodeUrl).forEach { sub ->
                subtitleCallback(SubtitleFile("35 • " + sub.language, sub.url))
            }

            val cacheKey = source.content + "|" + episodeUrl
            val initialCandidates = sourceCache.getOrPut(cacheKey) {
                SelcukFlixResolverBank.extractCandidates(
                    source.content,
                    episodeUrl,
                    mainUrl,
                    fromObservedApi = true
                )
            }
            val withPichive = expandPichiveCandidates(initialCandidates, episodeUrl, subtitleCallback)
            val candidates = expandNestedIframeCandidates(withPichive, episodeUrl)
            if (candidates.isEmpty()) {
                android.util.Log.w(
                    "SelcukFlix",
                    "36 • GenericExtractorFallback: NOT TESTED; no candidate URL could be extracted from source item " +
                        (sourceIndex + 1)
                )
            }

            candidates.forEach { candidate ->
                val label = sourceLabel(candidate, source)
                if (SelcukFlixResolverBank.isYouTubeUrl(candidate.url)) return@forEach

                val directLinkType = directType(candidate.url)
                if (directLinkType != null) {
                    callback(newExtractorLink(name, label, candidate.url, directLinkType) {
                        this.referer = candidate.referer
                        this.quality = Qualities.Unknown.value
                    })
                    totalResolved += 1

                    if (directLinkType == ExtractorLinkType.M3U8 && manifestChecked.add(candidate.url)) {
                        val manifestBody = try {
                            app.get(candidate.url, referer = candidate.referer).text
                        } catch (_: Exception) {
                            ""
                        }
                        if (manifestBody.isNotBlank() &&
                            !SelcukFlixResolverBank.isAccessChallenge(manifestBody)
                        ) {
                            SelcukFlixResolverBank.extractHlsVariants(manifestBody, candidate.url)
                                .forEach { variant ->
                                    val variantLabel = sourceLabel(variant, source)
                                    callback(newExtractorLink(name, variantLabel, variant.url, ExtractorLinkType.M3U8) {
                                        this.referer = candidate.url
                                        this.quality = Qualities.Unknown.value
                                    })
                                    totalResolved += 1
                                }
                            SelcukFlixResolverBank.extractHlsSubtitles(manifestBody, candidate.url)
                                .forEach { subtitle ->
                                    subtitleCallback(SubtitleFile("35 • " + subtitle.language, subtitle.url))
                                }
                        } else if (SelcukFlixResolverBank.isAccessChallenge(manifestBody)) {
                            android.util.Log.w(
                                "SelcukFlix",
                                "34 • SessionDependentSourceDiagnostic: BLOCKED at HLS manifest; no bypass attempted."
                            )
                        }
                    }
                    return@forEach
                }

                val playerKey = candidate.url + "|" + candidate.referer
                val resolvedLinks = playerCache[playerKey] ?: run {
                    val collected = mutableListOf<ExtractorLink>()
                    var subtitleIndex = 0
                    try {
                        loadExtractor(
                            candidate.url,
                            candidate.referer,
                            { sub ->
                                subtitleIndex += 1
                                subtitleCallback(SubtitleFile("35 • " + sub.lang, sub.url))
                            }
                        ) { link ->
                            if (!SelcukFlixResolverBank.isYouTubeUrl(link.url)) {
                                collected += link
                            }
                        }
                    } catch (error: Exception) {
                        android.util.Log.d(
                            "SelcukFlix",
                            "Resolver candidate failed; URL and query parameters intentionally omitted."
                        )
                    }
                    val safe = collected.distinctBy { it.url to it.type }
                    playerCache[playerKey] = safe
                    safe
                }

                if (resolvedLinks.isNotEmpty()) {
                    resolvedLinks.forEach { link ->
                        callback(newExtractorLink(name, label + " → " + link.name, link.url, link.type) {
                            this.referer = link.referer.ifBlank { candidate.referer }
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        })
                        totalResolved += 1
                    }
                } else {
                    // Keep an unresolved player URL visible as a manual diagnostic candidate.
                    // It is intentionally labelled so it cannot be mistaken for a verified media URL.
                    callback(newExtractorLink(name, label + " • PLAYER PAGE / UNRESOLVED", candidate.url, null) {
                        this.referer = candidate.referer
                        this.quality = Qualities.Unknown.value
                    })
                    totalResolved += 1
                }
            }
        }

        android.util.Log.i(
            "SelcukFlix",
            "Resolver test bank emitted " + totalResolved +
                " candidate link(s). A listed candidate is not proof of successful playback."
        )
        if (totalResolved == 0) {
            android.util.Log.w("SelcukFlix", "No playable/source candidate was emitted for this page.")
        }
        return totalResolved > 0
    }
}

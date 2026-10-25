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
    private val apiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://selcukflix.app/"
    )

    override val mainPage = mainPageOf(
        "movie:" to "Yeni Eklenen Filmler",
        "movie:49" to "Aile Filmleri",
        "movie:44" to "Animasyon Filmleri",
        "movie:59" to "Aksiyon Filmleri",
        "movie:66" to "Bilim Kurgu Filmleri",
        "movie:48" to "Dram Filmleri",
        "movie:61" to "Fantastik Filmleri",
        "movie:68" to "Gerilim Filmleri",
        "movie:51" to "Gizem Filmleri",
        "movie:63" to "Korku Filmleri",
        "movie:45" to "Komedi Filmleri",
        "movie:65" to "Romantik Filmleri",
        "movie:46" to "Suç Filmleri",
        "movie:69" to "Savaş Filmleri",
        "movie:78" to "Western Filmleri",
        "series:" to "Yeni Eklenen Diziler",
        "series:15" to "Aile Dizileri",
        "series:17" to "Animasyon Dizileri",
        "series:9" to "Aksiyon Dizileri",
        "series:5" to "Bilim Kurgu Dizileri",
        "series:2" to "Dram Dizileri",
        "series:12" to "Fantastik Dizileri",
        "series:18" to "Gerilim Dizileri",
        "series:3" to "Gizem Dizileri",
        "series:8" to "Korku Dizileri",
        "series:4" to "Komedi Dizileri",
        "series:7" to "Romantik Dizileri",
        "series:1" to "Suç Dizileri",
        "series:26" to "Savaş Dizileri",
        "series:11" to "Western Dizileri"
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

    private fun canonicalSiteUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val absolute = SelcukFlixResolverBank.normalizeUrl(raw, mainUrl) ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val legacyHosts = setOf("selcukflix.net", "selcukflix.com", "selcukflix.co")
        if (host in legacyHosts) {
            val suffix = (uri.rawPath ?: "/") +
                (uri.rawQuery?.let { "?" + it } ?: "") +
                (uri.rawFragment?.let { "#" + it } ?: "")
            return mainUrl.trimEnd('/') + suffix
        }
        return absolute
    }

    private fun responsePayload(body: String): String {
        if (SelcukFlixResolverBank.isAccessChallenge(body)) {
            throw ErrorLoadingException("SelcukFlix erişimi Cloudflare/CAPTCHA yanıtıyla engellendi; otomatik aşma yapılmadı.")
        }
        val parsed = runCatching { mapper.readTree(body) }.getOrNull() ?: return body
        val response = parsed.field("response")
        if (response == null || response.isNull) return body
        val value = if (response.isTextual) response.asText() else response.toString()
        if (value.looksLikeJson()) return value
        return SelcukFlixResolverBank.decodeBase64Text(value) ?: value
    }

    private suspend fun requestApi(path: String): JsonNode {
        val body = try {
            app.post(
                mainUrl.trimEnd('/') + path,
                headers = apiHeaders,
                referer = mainUrl.trimEnd('/') + "/"
            ).text
        } catch (error: Exception) {
            val kind = error.javaClass.simpleName.ifBlank { "NetworkError" }
            throw ErrorLoadingException(
                "SelcukFlix API isteği başarısız ($kind). /api/bg uçları .app üzerinde henüz canlı doğrulanamadı."
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
        val title = item.textField("originalTitle", "title", "name", "cultureTitle") ?: return null
        val url = canonicalSiteUrl(item.textField("usedSlug", "slug", "url", "link")) ?: return null
        val poster = normalizePoster(item.textField("posterUrl", "poster", "image", "thumbnail"))
        val score = item.textField("imdbPoint", "rating", "score")
        val typeText = item.textField("type", "contentType", "itemType").orEmpty().lowercase()
        val isSeries = url.contains("/dizi/", true) ||
            typeText in setOf("series", "tvseries", "tv series", "dizi", "tv-series")
        return if (isSeries) {
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
        val parts = request.data.split(":", limit = 2)
        val contentKind = parts.firstOrNull().orEmpty()
        val category = parts.getOrNull(1).orEmpty()
        val endpoint = if (contentKind.equals("series", true)) "findSeries" else "findMovies"
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val categoryParam = URLEncoder.encode(category, "UTF-8")
        val path = "/api/bg/" + endpoint +
            "?releaseYearStart=1900&releaseYearEnd=" + year +
            "&imdbPointMin=1&imdbPointMax=10&categoryIdsComma=" + categoryParam +
            "&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=" + page +
            "&currentPageCount=12&queryStr=&categorySlugsComma=&countryCodesComma="
        val root = requestApi(path)
        val results = resultArray(root).mapNotNull { asSearchResponse(it) }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = requestApi("/api/bg/searchcontent?searchterm=" + encoded)
        val state = root.field("state")
        if (state != null && state.isBoolean && !state.asBoolean()) {
            throw ErrorLoadingException("SelcukFlix arama API'si başarısız yanıt döndürdü.")
        }
        return resultArray(root).mapNotNull { asSearchResponse(it) }
            .filterNot { it.url.contains("/seri-filmler/", true) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun loadPageHtml(url: String, context: String): String {
        val html = try {
            app.get(url, referer = mainUrl.trimEnd('/') + "/").text
        } catch (error: Exception) {
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

    private fun episodesFrom(root: JsonNode): List<Episode> {
        val related = root.field("relatedResults") ?: root.field("RelatedResults") ?: return emptyList()
        val seasonsNode = related.field("getSerieSeasonAndEpisodes")?.field("seasons")
            ?: return emptyList()
        val entries = mutableListOf<Triple<Int, Int, Episode>>()
        var fallbackIndex = 0
        seasonsNode.children().forEach { seasonNode ->
            val seasonNumber = seasonNode.textField("seasonNo", "seasonNumber", "number")?.toIntOrNull()
            val episodesNode = seasonNode.field("episodes") ?: return@forEach
            episodesNode.children().forEach { episodeNode ->
                val episodeUrl = canonicalSiteUrl(episodeNode.textField("usedSlug", "slug", "url", "link"))
                    ?: return@forEach
                fallbackIndex += 1
                val episodeNumber = episodeNode.textField("episodeNo", "episodeNumber", "number")
                    ?.toIntOrNull() ?: fallbackIndex
                val episodeName = episodeNode.textField("epText", "episodeTitle", "title", "name")
                val episode = newEpisode(episodeUrl) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = normalizePoster(episodeNode.textField("posterUrl", "poster"))
                }
                entries += Triple(seasonNumber ?: 0, episodeNumber, episode)
            }
        }
        return entries.sortedWith(compareBy<Triple<Int, Int, Episode>> { it.first }.thenBy { it.second })
            .map { it.third }
            .distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse {
        val canonicalUrl = canonicalSiteUrl(url) ?: throw ErrorLoadingException("Geçersiz SelcukFlix içerik adresi.")
        val html = loadPageHtml(canonicalUrl, "detay")
        val root = decodedPageRoot(html)
        val item = root.field("contentItem") ?: root.field("ContentItem") ?: root
        val originalTitle = item.textField("originalTitle", "title", "name")
            ?: throw ErrorLoadingException("SelcukFlix detay verisinde başlık bulunamadı.")
        val cultureTitle = item.textField("cultureTitle", "localizedTitle").orEmpty()
        val title = if (cultureTitle.isNotBlank() && cultureTitle != originalTitle) {
            originalTitle + " - " + cultureTitle
        } else {
            originalTitle
        }
        val poster = normalizePoster(item.textField("posterUrl", "poster", "image"))
        val plot = item.textField("description", "plot", "overview", "summary")
        val year = item.textField("releaseYear", "year")?.toIntOrNull()
        val tags = item.textField("categories", "category", "genres")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        val rating = item.textField("imdbPoint", "rating", "score")
        val duration = item.textField("totalMinutes", "duration", "runtime")?.toIntOrNull()
        val related = root.field("relatedResults") ?: root.field("RelatedResults")
        val hasSeasons = related?.field("getSerieSeasonAndEpisodes") != null
        val isSeries = canonicalUrl.contains("/dizi/", true) || hasSeasons

        return if (isSeries) {
            val episodes = episodesFrom(root)
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
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

    private suspend fun expandNestedIframeCandidates(
        input: List<SelcukResolverCandidate>,
        episodeUrl: String
    ): List<SelcukResolverCandidate> {
        val output = input.toMutableList()
        val parents = input.filter { it.id == 15 }
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
            val candidates = expandNestedIframeCandidates(initialCandidates, episodeUrl)
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
        return totalResolved > 0 || sourceItems.isNotEmpty()
    }
}

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
    override var sequentialMainPage = true

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
        "episodes|all" to "Güncel Bölümler • Tümü",
        "episodes|foreign" to "Güncel Bölümler • Yabancı Dizi",
        "episodes|dub" to "Güncel Bölümler • Dublaj Dizi",
        "episodes|anime" to "Güncel Bölümler • Animeler",
        "api|findMovies" to "Yeni Eklenen Filmler",
        "api|findMovies|imdbPointMin=6.9" to "IMDb 6.9 Üstü",
        "api|findMovies|countryCodesComma=TR" to "Yerli Filmler",
        "$mainUrl/film-izle" to "Yeni Filmler",
        "api|findSeries" to "Yeni Eklenen Diziler",
        "$mainUrl/dizi-izle" to "Yabancı Diziler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/trend" to "Trend Diziler",
        "api|findSeries|countryIdsComma=21,26" to "Asya / Kore Dizileri",
        "api|findSeries|categoryIdsComma=17" to "Anime Dizileri",
        "api|findSeries|categoryIdsComma=9" to "Aksiyon Dizileri",
        "api|findSeries|categoryIdsComma=5" to "Bilim Kurgu Dizileri",
        "api|findSeries|categoryIdsComma=4" to "Komedi Dizileri",
        "api|findSeries|categoryIdsComma=2" to "Dram Dizileri",
        "api|findSeries|categoryIdsComma=12" to "Fantastik Diziler",
        "api|findSeries|categoryIdsComma=18" to "Gerilim Dizileri",
        "api|findSeries|categoryIdsComma=8" to "Korku Dizileri",
        "api|findSeries|categoryIdsComma=3" to "Gizem Dizileri",
        "api|findSeries|categoryIdsComma=24" to "Macera Dizileri",
        "api|findSeries|categoryIdsComma=7" to "Romantik Diziler",
        "api|findSeries|categoryIdsComma=1" to "Suç Dizileri",
        "api|findSeries|categoryIdsComma=15" to "Aile Dizileri",
        "api|findSeries|categoryIdsComma=26" to "Savaş Dizileri",
        "api|findSeries|categoryIdsComma=11" to "Western Diziler",
        "api|findMovies|categoryIdsComma=59" to "Aksiyon Filmleri",
        "api|findMovies|categoryIdsComma=66" to "Bilim Kurgu Filmleri",
        "api|findMovies|categoryIdsComma=45" to "Komedi Filmleri",
        "api|findMovies|categoryIdsComma=48" to "Dram Filmleri",
        "api|findMovies|categoryIdsComma=61" to "Fantastik Filmler",
        "api|findMovies|categoryIdsComma=68" to "Gerilim Filmleri",
        "api|findMovies|categoryIdsComma=63" to "Korku Filmleri",
        "api|findMovies|categoryIdsComma=51" to "Gizem Filmleri",
        "api|findMovies|categoryIdsComma=60" to "Macera Filmleri",
        "api|findMovies|categoryIdsComma=65" to "Romantik Filmler",
        "api|findMovies|categoryIdsComma=46" to "Suç Filmleri",
        "api|findMovies|categoryIdsComma=49" to "Aile Filmleri",
        "api|findMovies|categoryIdsComma=44" to "Animasyon Filmleri",
        "api|findMovies|categoryIdsComma=69" to "Savaş Filmleri",
        "api|findMovies|categoryIdsComma=78" to "Western Filmler"
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

    private suspend fun requestApi(
        path: String,
        params: Map<String, String> = emptyMap(),
        referrer: String = "$mainUrl/"
    ): JsonNode {
        val encodedParams = params.entries.joinToString("&") { entry ->
            URLEncoder.encode(entry.key, "UTF-8") + "=" + URLEncoder.encode(entry.value, "UTF-8")
        }
        val requestPath = path + when {
            encodedParams.isBlank() -> ""
            path.contains("?") -> "&$encodedParams"
            else -> "?$encodedParams"
        }
        val body = try {
            app.post(
                mainUrl.trimEnd('/') + requestPath,
                headers = apiHeaders + ("Referer" to referrer),
                referer = referrer
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
            throw ErrorLoadingException("SelcukFlix API isteği başarısız ($kind).")
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

    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val url = canonicalSiteUrl(raw) ?: SelcukFlixResolverBank.normalizeUrl(raw, mainUrl) ?: return null
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        if (listOf("placeholder", "no-poster", "no_poster", "poster-not-found",
                "no-image", "no_image", "default-poster", "blank.gif").any { path.contains(it) }) return null
        if (Regex("""(?i)\.(svg|ico)(?:$|[?#])""").containsMatchIn(path)) return null
        return url
    }

    private fun posterForItem(item: JsonNode): String? {
        val direct = item.textField(
            "object_poster_url", "object_face_url", "poster_url", "face_url",
            "objectPosterUrl", "objectFaceUrl", "posterUrl", "faceUrl",
            "poster_path", "posterPath", "image_url", "imageUrl", "image",
            "thumbnail_url", "thumbnailUrl", "thumbnail", "thumb_url", "cover_url", "cover",
            "backdrop_url", "backdrop", "banner"
        )?.let(::normalizePoster)
        return direct ?: SelcukFlixResolverBank.findPosterUrl(item, mainUrl)?.let(::normalizePoster)
    }

    private fun posterFromImage(image: org.jsoup.nodes.Element?): String? {
        if (image == null) return null
        val values = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-original-src"),
            image.attr("data-lazy"),
            image.attr("data-url"),
            image.attr("src"),
            image.attr("srcset"),
            image.attr("data-srcset")
        )
        for (value in values) {
            if (value.isBlank()) continue
            val candidates = if (value.contains(",")) {
                value.split(",").map { it.trim().substringBefore(' ') }
            } else {
                listOf(value.trim().substringBefore(' '))
            }
            for (candidate in candidates) {
                val normalized = normalizePoster(candidate)
                if (normalized != null) return normalized
            }
        }
        return null
    }

    private fun posterFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): String? {
        val meta = document.selectFirst(
            "meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image], meta[name=twitter:image:src]"
        )?.attr("content")?.let(::normalizePoster)
        if (meta != null) return meta
        document.select("img").forEach { image ->
            posterFromImage(image)?.let { return it }
        }
        return null
    }

    private fun asSearchResponse(item: JsonNode): SearchResponse? {
        val title = item.textField(
            "object_name", "object_alternative_name", "used_title",
            "original_title", "originalTitle", "culture_title", "cultureTitle",
            "title", "name"
        ) ?: return null
        val rawUrl = item.textField("used_slug", "usedSlug", "slug", "url", "link") ?: return null
        val url = canonicalSiteUrl(rawUrl) ?: return null
        val poster = posterForItem(item)
        val score = item.textField("imdb_point", "imdbPoint", "object_related_imdb_point", "rating", "score")
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

    private suspend fun getApiCategory(page: Int, request: MainPageRequest): HomePageResponse {
        val pieces = request.data.split("|")
        val endpoint = pieces.getOrNull(1)?.takeIf { it in setOf("findMovies", "findSeries") }
            ?: throw ErrorLoadingException("SelcukFlix kategori uç noktası tanınmadı.")
        val filters = linkedMapOf<String, String>()
        pieces.drop(2).forEach { part ->
            val at = part.indexOf('=')
            if (at > 0) filters[part.substring(0, at)] = part.substring(at + 1)
        }
        val params = linkedMapOf(
            "releaseYearStart" to "1900",
            "releaseYearEnd" to Calendar.getInstance().get(Calendar.YEAR).toString(),
            "imdbPointMin" to "0",
            "imdbPointMax" to "10",
            "categoryIdsComma" to "",
            "countryIdsComma" to "",
            "orderType" to "date_desc",
            "languageId" to "-1",
            "currentPage" to page.toString(),
            "currentPageCount" to "24",
            "queryStr" to "",
            "categorySlugsComma" to "",
            "countryCodesComma" to ""
        ).apply { putAll(filters) }
        // Page values are internal controls, not category overrides.
        params["currentPage"] = page.toString()
        params["currentPageCount"] = "24"

        val root = requestApi("/api/bg/$endpoint", params, "$mainUrl/kesfet")
        val items = resultArray(root).mapNotNull(::asSearchResponse).distinctBy { it.url }
        val hasMore = root.field("pagination")?.field("hasMore")?.asBoolean()
            ?: root.field("hasMore")?.asBoolean()
            ?: (items.size >= 24)
        return newHomePageResponse(request.name, items, hasNext = hasMore && items.isNotEmpty())
    }

    private fun episodeMetadataText(
        anchor: org.jsoup.nodes.Element,
        heading: String,
        episodeLabel: String
    ): String {
        val parts = mutableListOf(heading, episodeLabel)
        var element: org.jsoup.nodes.Element? = anchor
        val metadataAttributes = listOf(
            "class", "id", "data-type", "data-category", "data-genre",
            "data-language", "data-lang", "data-version", "data-filter", "data-tab",
            "aria-label", "title"
        )
        repeat(3) {
            val current = element ?: return@repeat
            parts += current.text()
            metadataAttributes.forEach { attribute ->
                if (current.hasAttr(attribute)) parts += current.attr(attribute)
            }
            element = current.parent()
        }
        return parts.joinToString(" ").lowercase()
    }

    private fun latestEpisodeCard(anchor: org.jsoup.nodes.Element): SearchResponse? {
        val href = canonicalSiteUrl(anchor.attr("href")) ?: return null
        val path = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
        val isEpisode = path.contains("/bolum-")
        if (!isEpisode && !path.startsWith("/dizi/")) return null
        val heading = anchor.selectFirst("h3, h2, [data-title]")?.text()?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: anchor.selectFirst("img")?.let { it.attr("alt").trim() }
            ?: anchor.attr("title").trim()
        if (heading.isBlank()) return null
        val episodeLabel = anchor.selectFirst("h3 + div, h3 + span, .episode-info, .episode-title")
            ?.text()?.trim().orEmpty()
        val mode = currentEpisodeFilter
        val combined = episodeMetadataText(anchor, heading, episodeLabel)
        if (!SelcukFlixResolverBank.matchesEpisodeFilter(combined, mode)) return null

        val title = if (episodeLabel.isBlank()) heading else "$heading - $episodeLabel"
        val seriesUrl = if (isEpisode) {
            // Keep the episode URL so the loader can map it back using FindedType/series metadata.
            href
        } else {
            href.substringBefore("/sezon-").substringBefore("/bolum-")
        }
        val poster = posterFromImage(anchor.selectFirst("img"))
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { posterUrl = poster }
    }

    private var currentEpisodeFilter: String = "all"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("api|")) return getApiCategory(page, request)

        val episodeFilter = request.data.startsWith("episodes|")
        currentEpisodeFilter = if (episodeFilter) request.data.substringAfter("|") else "all"
        // The site shows "Güncel Bölümler" as a homepage section; do not assume a hidden route.
        val base = if (episodeFilter) "$mainUrl/"
            else (canonicalSiteUrl(request.data) ?: request.data)
        val pageUrl = if (page > 1) {
            base + (if (base.contains("?")) "&" else "?") + "page=" + page
        } else base
        val html = loadPageHtml(pageUrl, "katalog")
        val document = Jsoup.parse(html, pageUrl)
        val result = mutableListOf<SearchResponse>()

        val latestEpisodes = episodeFilter
        if (latestEpisodes) {
            document.select("a[href*='/bolum-'], a[href*='/dizi/']")
                .filter { it.selectFirst("h3, h2, img, [data-title]") != null }
                .forEach { anchor -> latestEpisodeCard(anchor)?.let(result::add) }
        } else {
            val nextDataText = document.selectFirst("script#__NEXT_DATA__")?.data()
                ?: document.selectFirst("script#__NEXT_DATA__")?.html()
            val nextRoot = nextDataText?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            val pageProps = nextRoot?.field("props")?.field("pageProps")
            val initialSeries = pageProps?.field("initialSeries")
            if (initialSeries != null && !initialSeries.isNull) {
                val encoded = if (initialSeries.isTextual) initialSeries.asText() else initialSeries.toString()
                val decoded = SelcukFlixResolverBank.decodeSitePayload(encoded) ?: encoded
                val items = runCatching { mapper.readTree(decoded) }.getOrNull()
                if (items?.isArray == true) items.forEach { item -> asSearchResponse(item)?.let(result::add) }
            }

            if (result.isEmpty()) {
                document.select(
                    "a[href^='dizi/'], a[href^='/dizi/'], a[href^='film/'], a[href^='/film/'], " +
                        "a[href*='/dizi/'], a[href*='/film/']"
                ).forEach { anchor ->
                    val href = canonicalSiteUrl(anchor.attr("href")) ?: return@forEach
                    val uriPath = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
                    if (uriPath.contains("/sezon-") || uriPath.contains("/bolum-")) return@forEach
                    val image = anchor.selectFirst("img")
                    val title = image?.attr("alt").orEmpty().ifBlank { anchor.attr("title") }
                        .ifBlank { anchor.selectFirst("h2, h3")?.text().orEmpty() }
                        .ifBlank { anchor.text() }
                        .removeSuffix(" izle").removeSuffix(" full hd izle").trim()
                    if (title.isBlank()) return@forEach
                    val poster = posterFromImage(image)
                    result += if (uriPath.startsWith("/film/")) {
                        newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
                    } else {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                    }
                }
            }
        }

        val unique = result.distinctBy { it.url }
        return newHomePageResponse(request.name, unique, hasNext = unique.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val params = mapOf("searchterm" to query)
        val roots = mutableListOf<JsonNode>()
        // The current site client and older mirrors use different casing for this route.
        runCatching { requestApi("/api/bg/searchContent", params, "$mainUrl/") }
            .getOrNull()?.let(roots::add)
        if (roots.isEmpty() || roots.all { resultArray(it).isEmpty() }) {
            runCatching { requestApi("/api/bg/searchcontent", params, "$mainUrl/") }
                .getOrNull()?.let(roots::add)
        }
        val root = roots.firstOrNull { resultArray(it).isNotEmpty() } ?: roots.firstOrNull()
            ?: throw ErrorLoadingException("SelcukFlix arama API'sinden yanıt alınamadı.")
        val state = root.field("state")
        if (state != null && state.isBoolean && !state.asBoolean()) {
            throw ErrorLoadingException("SelcukFlix arama API'si başarısız yanıt döndürdü.")
        }
        return resultArray(root).mapNotNull(::asSearchResponse)
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

    private fun pageDataResult(root: JsonNode?): JsonNode? =
        root?.field("content")?.field("result")
            ?: root?.field("Content")?.field("Result")
            ?: root

    override suspend fun load(url: String): LoadResponse {
        var canonicalUrl = canonicalSiteUrl(url)
            ?: throw ErrorLoadingException("Geçersiz SelcukFlix içerik adresi.")
        var html = loadPageHtml(canonicalUrl, "detay")
        var document = Jsoup.parse(html, canonicalUrl)
        var root = runCatching { decodedPageRoot(html) }.getOrNull()
        var pageRoot = pageDataResult(root)

        // "Güncel Bölümler" links point to episode URLs. Resolve those to their owning series.
        if (pageRoot?.textField("FindedType").orEmpty().equals("Episodes", ignoreCase = true)) {
            val related = pageRoot?.field("RelatedResults") ?: pageRoot?.field("relatedResults")
            val seriesSlug = related?.field("getSeriesDetail")?.field("result")
                ?.textField("used_slug", "usedSlug", "slug")
                ?: related?.field("seriesDetail")?.field("result")
                    ?.textField("used_slug", "usedSlug", "slug")
            val seriesUrl = canonicalSiteUrl(seriesSlug)
            if (seriesUrl != null && seriesUrl != canonicalUrl) {
                canonicalUrl = seriesUrl
                html = loadPageHtml(canonicalUrl, "dizi detay")
                document = Jsoup.parse(html, canonicalUrl)
                root = runCatching { decodedPageRoot(html) }.getOrNull()
                pageRoot = pageDataResult(root)
            }
        }

        // Current hydrated pages use content.result.FindedResult.result; legacy page data is a fallback.
        val item = pageRoot?.field("FindedResult")?.field("result")
            ?: pageRoot?.field("contentItem")
            ?: pageRoot?.field("ContentItem")
            ?: root?.field("contentItem")
            ?: root?.field("ContentItem")
            ?: pageRoot
        val title = item?.textField("original_title", "originalTitle", "culture_title", "cultureTitle", "title", "name")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.removeSuffix(" izle")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("SelcukFlix detay sayfasında başlık bulunamadı.")

        val description = item?.textField("description", "plot", "overview", "summary")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val poster = item?.let(::posterForItem) ?: posterFromDocument(document, canonicalUrl)

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

        val htmlEpisodes = episodeEntries
            .sortedWith(compareBy<Triple<Int, Int, Episode>> { it.first }.thenBy { it.second })
            .map { it.third }
        // Prefer the site's structured episode payload; use season-page HTML as a fallback.
        val episodes = root?.let(::episodesFrom)?.takeIf { it.isNotEmpty() } ?: htmlEpisodes
        val path = runCatching { URI(canonicalUrl).path.orEmpty().lowercase() }.getOrDefault("")
        val findedType = pageRoot?.textField("FindedType").orEmpty()
        val isMovie = findedType.equals("Movies", ignoreCase = true) ||
            (findedType.isBlank() && path.startsWith("/film/"))
        val isSeries = !isMovie && (
            findedType.isNotBlank() || path.startsWith("/dizi/") || seasonLinks.isNotEmpty() ||
                episodes.isNotEmpty() || pageRoot?.field("RelatedResults")?.field("getSerieSeasonAndEpisodes") != null
            )

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
                val contentKeys = listOf(
                    "source_content", "sourceContent", "sourceUrl", "source_url",
                    "embedUrl", "embed_url", "videoUrl", "video_url"
                )
                val content = contentKeys.asSequence().mapNotNull { key ->
                    val value = node.field(key) ?: return@mapNotNull null
                    if (value.isNull) return@mapNotNull null
                    when {
                        value.isTextual -> value.asText().trim().takeIf { it.isNotBlank() && it != "null" }
                        value.isContainerNode -> value.toString()
                        else -> null
                    }
                }.firstOrNull()
                if (!content.isNullOrBlank()) {
                    // Preserve the player's own label and audio/language variant so a dubbed
                    // source is not hidden behind a generic "Kaynak 1" entry.
                    val labelParts = listOf(
                        node.textField("source_name", "sourceName", "provider_name", "providerName",
                            "player_name", "playerName", "name"),
                        node.textField("language_name", "languageName", "source_language", "sourceLanguage",
                            "audio_type", "audioType", "language", "lang", "version", "audio"),
                        node.textField("quality_name", "qualityName", "quality", "label")
                    ).filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    val label = labelParts.joinToString(" • ")
                        .ifBlank { "Kaynak " + (found.size + 1) }
                    val key = label + "|" + content
                    found.putIfAbsent(key, SourceItem(content, label))
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
            it.id == 15 &&
                SelcukFlixResolverBank.isPichiveIframe(it.url) &&
                !SelcukFlixContentXSourceParser.canHandle(it.url)
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
            it.id == 15 &&
                !SelcukFlixResolverBank.isPichiveIframe(it.url) &&
                !SelcukFlixContentXSourceParser.canHandle(it.url)
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
        val pageDocument = Jsoup.parse(html, episodeUrl)
        val decodedPayload = SelcukFlixResolverBank.extractEmbeddedPayload(html)

        // Do not let one stale/missing page-state field suppress all other discovery paths.
        // Each surface is scanned independently and the resolver IDs remain attached to outputs.
        val payloadSurfaces = linkedMapOf<String, String>()
        if (!decodedPayload.isNullOrBlank()) payloadSurfaces["Decoded page payload"] = decodedPayload
        pageDocument.selectFirst("script#__NEXT_DATA__")?.let { script ->
            val text = script.data().ifBlank { script.html() }
            if (text.isNotBlank()) payloadSurfaces.putIfAbsent("Next.js page state", text)
        }
        pageDocument.select("script").forEachIndexed { index, script ->
            val text = script.data().ifBlank { script.html() }
            if (text.isNotBlank() && Regex(
                    """(?is)(source_content|sourceContent|openPlayer|source2\.php|iframe\.php|player|manifest|m3u8|mpd)"""
                ).containsMatchIn(text)
            ) {
                payloadSurfaces.putIfAbsent("Embedded player script ${index + 1}", text)
            }
        }
        payloadSurfaces.putIfAbsent("Raw detail HTML", html)

        val sourceItems = mutableListOf<SourceItem>()
        payloadSurfaces.forEach { (surfaceName, surfaceText) ->
            val parsed = runCatching { mapper.readTree(surfaceText) }.getOrNull()
            val structured = parsed?.let(::collectSourceItems).orEmpty()
            structured.forEach { item ->
                sourceItems += SourceItem(item.content, "$surfaceName • ${item.label}")
            }
            // Keep the entire surface as an independent fallback for HTML/JSON/script/escaped URL parsers.
            sourceItems += SourceItem(surfaceText, surfaceName)
        }

        var totalResolved = 0
        val manifestChecked = mutableSetOf<String>()
        val playerCache = mutableMapOf<String, List<ExtractorLink>>()
        val sourceCache = mutableMapOf<String, List<SelcukResolverCandidate>>()
        val contentXCache = mutableMapOf<String, List<ExtractorLink>>()
        val processedCandidates = mutableSetOf<String>()
        val emittedResolverLinks = mutableSetOf<String>()

        sourceItems.distinctBy { it.label + "|" + it.content }.forEachIndexed { sourceIndex, source ->
            SelcukFlixResolverBank.extractSubtitles(source.content, episodeUrl).forEach { sub ->
                subtitleCallback(newSubtitleFile("35 • " + sub.language, sub.url) {
                    headers = browserHeaders + mapOf("Referer" to episodeUrl)
                })
            }

            val cacheKey = source.content + "|" + episodeUrl
            val initialCandidates = sourceCache.getOrPut(cacheKey) {
                SelcukFlixResolverBank.extractCandidates(
                    source.content,
                    episodeUrl,
                    mainUrl,
                    fromObservedApi = source.label.contains("source", true) ||
                        source.label.contains("API", true) ||
                        source.label.contains("Pichive", true)
                )
            }
            // These are additional independently named discoveries, not replacements for IDs
            // that found the same iframe through HTML, JSON, script, or a player configuration.
            val sourceIframes = SelcukFlixResolverBank.extractSourceIframes(source.content, episodeUrl)
            val allInitial = (
                initialCandidates + sourceIframes.map { iframe ->
                    SelcukResolverCandidate(
                        id = 15,
                        url = iframe,
                        kind = SelcukMediaKind.PLAYER,
                        referer = episodeUrl,
                        note = "observed source_content iframe"
                    )
                }
            ).distinctBy { it.id.toString() + "|" + it.url + "|" + it.referer + "|" + it.note }
            val withPichive = expandPichiveCandidates(allInitial, episodeUrl, subtitleCallback)
            val candidates = expandNestedIframeCandidates(withPichive, episodeUrl)
            if (candidates.isEmpty()) {
                android.util.Log.w(
                    "SelcukFlix",
                    "No iframe or media URL found in surface " + (sourceIndex + 1) +
                        " (${source.label}); source schema or request flow may have changed."
                )
            }

            candidates.forEach { candidate ->
                val label = sourceLabel(candidate, source)
                if (SelcukFlixResolverBank.isYouTubeUrl(candidate.url)) return@forEach
                val candidateKey = candidate.id.toString() + "|" + candidate.url + "|" +
                    candidate.referer + "|" + candidate.note
                if (!processedCandidates.add(candidateKey)) return@forEach

                // ContentX/Pichive iframe.php hosts are dynamic. Cache the network result per
                // URL + header context, but emit a separately named result for each discovery ID.
                if (SelcukFlixContentXSourceParser.canHandle(candidate.url)) {
                    val contextKey = candidate.url + "|" + candidate.referer
                    val resolvedLinks = contentXCache[contextKey] ?: run {
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            SelcukFlixContentXExtractor().getUrl(
                                candidate.url,
                                candidate.referer.ifBlank { episodeUrl },
                                subtitleCallback
                            ) { link ->
                                if (!SelcukFlixResolverBank.isYouTubeUrl(link.url)) collected += link
                            }
                        } catch (_: Exception) {
                            android.util.Log.w(
                                "SelcukFlixContentX",
                                "Observed iframe resolver failed for resolver " +
                                    candidate.id.toString().padStart(2, '0') +
                                    "; URL and token omitted from log."
                            )
                        }
                        collected.distinctBy { it.url to it.type }.also { contentXCache[contextKey] = it }
                    }
                    if (resolvedLinks.isEmpty()) {
                        android.util.Log.w(
                            "SelcukFlixContentX",
                            "${candidate.id.toString().padStart(2, '0')} • ${SelcukFlixResolverBank.resolverName(candidate.id)}: no media file emitted for this request context."
                        )
                    }
                    resolvedLinks.forEach { link ->
                        val emitKey = candidateKey + "|" + link.url + "|" + link.type
                        if (!emittedResolverLinks.add(emitKey)) return@forEach
                        callback(newExtractorLink(name, label + " → " + link.name, link.url, link.type) {
                            this.referer = link.referer.ifBlank { candidate.referer }
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        })
                        totalResolved += 1
                    }
                    return@forEach
                }

                val directLinkType = directType(candidate.url)
                if (directLinkType != null) {
                    val emitKey = candidateKey + "|" + candidate.url + "|" + directLinkType
                    if (emittedResolverLinks.add(emitKey)) {
                        callback(newExtractorLink(name, label, candidate.url, directLinkType) {
                            this.referer = candidate.referer
                            this.quality = Qualities.Unknown.value
                            this.headers = browserHeaders + mapOf("Referer" to candidate.referer)
                        })
                        totalResolved += 1
                    }

                    if (directLinkType == ExtractorLinkType.M3U8 && manifestChecked.add(candidate.url)) {
                        val manifestBody = try {
                            app.get(
                                candidate.url,
                                headers = browserHeaders + mapOf("Referer" to candidate.referer),
                                referer = candidate.referer
                            ).text
                        } catch (_: Exception) {
                            ""
                        }
                        if (manifestBody.isNotBlank() &&
                            !SelcukFlixResolverBank.isAccessChallenge(manifestBody)
                        ) {
                            SelcukFlixResolverBank.extractHlsVariants(manifestBody, candidate.url)
                                .forEach { variant ->
                                    val variantLabel = sourceLabel(variant, source)
                                    val variantKey = variant.id.toString() + "|" + variant.url + "|" +
                                        variant.referer + "|" + variant.note
                                    if (emittedResolverLinks.add(variantKey + "|" + variant.url)) {
                                        callback(newExtractorLink(name, variantLabel, variant.url, ExtractorLinkType.M3U8) {
                                            this.referer = candidate.url
                                            this.quality = Qualities.Unknown.value
                                            this.headers = browserHeaders + mapOf("Referer" to candidate.url)
                                        })
                                        totalResolved += 1
                                    }
                                }
                            SelcukFlixResolverBank.extractHlsSubtitles(manifestBody, candidate.url)
                                .forEach { subtitle ->
                                    subtitleCallback(newSubtitleFile("35 • " + subtitle.language, subtitle.url) {
                                        headers = browserHeaders + mapOf("Referer" to candidate.url)
                                    })
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
                    try {
                        loadExtractor(
                            candidate.url,
                            candidate.referer,
                            { sub ->
                                subtitleCallback(
                                    SubtitleFile(sub.lang, sub.url).apply {
                                        headers = sub.headers ?: mapOf("Referer" to candidate.url)
                                    }
                                )
                            }
                        ) { link ->
                            if (!SelcukFlixResolverBank.isYouTubeUrl(link.url)) collected += link
                        }
                    } catch (_: Exception) {
                        android.util.Log.d(
                            "SelcukFlix",
                            "${candidate.id.toString().padStart(2, '0')} • ${SelcukFlixResolverBank.resolverName(candidate.id)} failed; URL and query parameters intentionally omitted."
                        )
                    }
                    collected.distinctBy { it.url to it.type }.also { playerCache[playerKey] = it }
                }

                if (resolvedLinks.isNotEmpty()) {
                    resolvedLinks.forEach { link ->
                        val emitKey = candidateKey + "|" + link.url + "|" + link.type
                        if (!emittedResolverLinks.add(emitKey)) return@forEach
                        callback(newExtractorLink(name, label + " → " + link.name, link.url, link.type) {
                            this.referer = link.referer.ifBlank { candidate.referer }
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        })
                        totalResolved += 1
                    }
                } else {
                    android.util.Log.d(
                        "SelcukFlix",
                        "${candidate.id.toString().padStart(2, '0')} • ${SelcukFlixResolverBank.resolverName(candidate.id)}: candidate did not resolve to media."
                    )
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
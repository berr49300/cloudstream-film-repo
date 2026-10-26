package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

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

    // These four public HTML routes are the catalog routes used by the current site.
    // Do not advertise guessed /api/bg/findMovies or /findSeries endpoints as catalog rows.
    override val mainPage = mainPageOf(
        "episodes|all" to "Güncel Bölümler • Tümü",
        "episodes|foreign" to "Güncel Bölümler • Altyazılı",
        "episodes|dub" to "Güncel Bölümler • Dublaj",
        "episodes|anime" to "Güncel Bölümler • Anime",
        "$mainUrl/dizi-izle" to "Yeni Diziler",
        "$mainUrl/film-izle" to "Yeni Filmler",
        "$mainUrl/trend" to "Trend",
        "$mainUrl/kesfet" to "Keşfet"
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

    private fun normalizePoster(raw: String?, baseUrl: String = mainUrl): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        val url = if (value.startsWith("http://", true) ||
            value.startsWith("https://", true) || value.startsWith("//") ||
            value.startsWith("selcukflix.app", true)
        ) {
            canonicalSiteUrl(value) ?: SelcukFlixResolverBank.normalizeUrl(value, baseUrl)
        } else {
            SelcukFlixResolverBank.normalizeUrl(value, baseUrl)
        } ?: return null
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        if (listOf("placeholder", "no-poster", "no_poster", "poster-not-found",
                "no-image", "no_image", "default-poster", "blank.gif").any { path.contains(it) }) return null
        if (Regex("""(?i)\.(svg|ico)(?:$|[?#])""").containsMatchIn(path)) return null
        return url
    }

    private fun posterForItem(item: JsonNode): String? {
        // Explicit poster/face fields must win over backdrop or generic image fields.
        val directPoster = item.textField(
            "object_poster_url", "poster_url", "posterUrl", "poster_path", "posterPath",
            "object_face_url", "face_url", "faceUrl", "poster", "face",
            "cover_url", "cover", "thumbnail_url", "thumbnailUrl", "thumbnail", "thumb_url", "thumb"
        )?.let(::normalizePoster)
        if (directPoster != null) return directPoster

        // Search nested API objects before falling back to a generic image/backdrop field.
        val nested = SelcukFlixResolverBank.findPosterUrl(item, mainUrl)?.let(::normalizePoster)
        if (nested != null) return nested
        return item.textField("image_url", "imageUrl", "image", "backdrop_url", "backdrop", "banner")
            ?.let(::normalizePoster)
    }

    private fun posterFromImage(image: org.jsoup.nodes.Element?, baseUrl: String = mainUrl): String? {
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
                val normalized = normalizePoster(candidate, baseUrl)
                if (normalized != null) return normalized
            }
        }
        return null
    }

    private fun posterFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): String? {
        val meta = document.select(
            "meta[property=og:image], meta[property=og:image:url], meta[name=twitter:image], meta[name=twitter:image:src]"
        ).mapNotNull { normalizePoster(it.attr("content"), baseUrl) }.firstOrNull()
        if (meta != null) return meta
        document.select("img").forEach { image ->
            posterFromImage(image, baseUrl)?.let { return it }
        }
        return null
    }

    private fun asSearchResponse(item: JsonNode, posterOverride: String? = null): SearchResponse? {
        val title = item.textField(
            "object_name", "object_alternative_name", "used_title",
            "original_title", "originalTitle", "culture_title", "cultureTitle",
            "title", "name"
        ) ?: return null
        val rawUrl = item.textField("used_slug", "usedSlug", "slug", "url", "link") ?: return null
        val url = canonicalSiteUrl(rawUrl) ?: return null
        val poster = posterForItem(item) ?: normalizePoster(posterOverride)
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
                posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
                this.score = Score.from10(score)
            }
        }
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
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = poster
            posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
        }
    }

    private var currentEpisodeFilter: String = "all"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                        newMovieSearchResponse(title, href, TvType.Movie) {
                            posterUrl = poster
                            posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
                        }
                    } else {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            posterUrl = poster
                            posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
                        }
                    }
                }
            }
        }

        val unique = result.distinctBy { it.url }

        // If initialSeries supplied metadata without poster aliases, reuse the image on the
        // visible catalogue card before issuing any detail request. This avoids unnecessary
        // per-item network calls and repairs the common nested-card/data-URL variant.
        if (!episodeFilter && page == 1) {
            val cardPosters = linkedMapOf<String, String>()
            document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { anchor ->
                val href = canonicalSiteUrl(anchor.attr("href")) ?: return@forEach
                val cardPath = runCatching { URI(href).path.orEmpty().lowercase() }.getOrDefault("")
                if (cardPath.contains("/sezon-") || cardPath.contains("/bolum-")) return@forEach
                val poster = posterFromImage(anchor.selectFirst("img"), href) ?: return@forEach
                cardPosters.putIfAbsent(href, poster)
            }
            unique.forEach { item ->
                if (item.posterUrl.isNullOrBlank()) {
                    val poster = cardPosters[item.url]
                    if (!poster.isNullOrBlank()) {
                        item.posterUrl = poster
                        item.posterHeaders = browserHeaders + ("Referer" to "$mainUrl/")
                    }
                }
            }
        }

        // Only fetch a small, bounded set of detail pages for posters that are still missing.
        val routePath = runCatching { URI(base).path.orEmpty().lowercase() }.getOrDefault("")
        if (!episodeFilter && page == 1 && routePath in setOf("/dizi-izle", "/film-izle")) {
            var detailLookups = 0
            unique.forEach { item ->
                if (detailLookups >= 8) return@forEach
                if (!item.posterUrl.isNullOrBlank()) return@forEach
                detailLookups++
                val poster = runCatching {
                    val detailHtml = loadPageHtml(item.url, "katalog poster fallback")
                    posterFromDocument(Jsoup.parse(detailHtml, item.url), item.url)
                }.getOrNull()
                if (!poster.isNullOrBlank()) {
                    item.posterUrl = poster
                    item.posterHeaders = browserHeaders + ("Referer" to item.url)
                    android.util.Log.d("SelcukFlixPoster", "Recovered a missing poster from a detail-page image.")
                } else {
                    android.util.Log.d("SelcukFlixPoster", "No detail-page poster was available for a missing poster.")
                }
            }
        }

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
                posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                posterHeaders = if (poster.isNullOrBlank()) null else browserHeaders + ("Referer" to "$mainUrl/")
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

    private fun iframeUrlsFromText(raw: String, baseUrl: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = raw
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&", ignoreCase = true)
        val found = linkedSetOf<String>()
        val document = Jsoup.parse(text, baseUrl)
        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { frame ->
            val candidate = frame.attr("src")
                .ifBlank { frame.attr("data-src") }
                .ifBlank { frame.attr("data-lazy-src") }
            SelcukFlixResolverBank.normalizeUrl(candidate, baseUrl)?.let(found::add)
        }
        Regex(
            """(?is)<iframe\b[^>]*?\b(?:src|data-src|data-lazy-src)\s*=\s*\\?["']([^"'\\]+)"""
        ).findAll(text).forEach { match ->
            SelcukFlixResolverBank.normalizeUrl(match.groupValues[1], baseUrl)?.let(found::add)
        }
        Regex(
            """(?i)(?:https?:)?//[^\s"'<>\\]+/iframe\.php\?[^"'<>\\\s]*"""
        ).findAll(text).forEach { match ->
            SelcukFlixResolverBank.normalizeUrl(match.value, baseUrl)?.let(found::add)
        }
        return found.toList()
    }

    private fun directMediaUrlsFromText(raw: String, baseUrl: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = raw
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        val found = linkedSetOf<String>()
        val urlPattern = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+""")
        urlPattern.findAll(text).forEach { match ->
            val candidate = match.value.trimEnd(')', ']', '}', ',', ';', '.')
            val normalized = SelcukFlixResolverBank.normalizeUrl(candidate, baseUrl) ?: return@forEach
            val path = runCatching { URI(normalized).path.orEmpty().lowercase() }.getOrDefault("")
            if (path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || path.endsWith(".mkv") ||
                path.endsWith(".avi") || path.endsWith(".mov")
            ) found.add(normalized)
        }
        return found.toList()
    }

    private fun directMediaType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
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
        val html = try {
            loadPageHtml(episodeUrl, "oynatıcı/kaynak")
        } catch (_: Exception) {
            return false
        }
        if (SelcukFlixResolverBank.isAccessChallenge(html)) return false

        val payloads = linkedMapOf<String, String>()
        payloads["Raw detail HTML"] = html
        val embedded = SelcukFlixResolverBank.extractEmbeddedPayload(html)
        if (!embedded.isNullOrBlank()) payloads["Decoded page payload"] = embedded
        val document = Jsoup.parse(html, episodeUrl)
        document.select("script").forEachIndexed { index, script ->
            val text = script.data().ifBlank { script.html() }
            if (text.isNotBlank() && Regex(
                    """(?is)(source_content|sourceContent|iframe\.php|openPlayer|source2\.php|m3u8|mpd)"""
                ).containsMatchIn(text)
            ) payloads.putIfAbsent("Player script " + (index + 1), text)
        }

        val sourceItems = mutableListOf<SourceItem>()
        payloads.forEach { (label, text) ->
            val parsed = runCatching { mapper.readTree(text) }.getOrNull()
            if (parsed != null) {
                sourceItems += collectSourceItems(parsed).map { item ->
                    SourceItem(item.content, "$label • " + item.label)
                }
            }
            // Keep each observed representation as a separate fallback, not as a fake video URL.
            sourceItems += SourceItem(text, label)
        }

        val iframeCandidates = linkedMapOf<String, String>()
        val directCandidates = linkedMapOf<String, String>()
        sourceItems.forEachIndexed { index, source ->
            iframeUrlsFromText(source.content, episodeUrl).forEach { url ->
                if (!SelcukFlixResolverBank.isYouTubeUrl(url)) {
                    iframeCandidates.putIfAbsent(url, source.label)
                }
            }
            directMediaUrlsFromText(source.content, episodeUrl).forEach { url ->
                if (!SelcukFlixResolverBank.isYouTubeUrl(url)) {
                    directCandidates.putIfAbsent(url, source.label.ifBlank { "Doğrudan kaynak " + (index + 1) })
                }
            }
        }

        var emitted = false
        directCandidates.forEach { (url, label) ->
            val type = directMediaType(url) ?: return@forEach
            callback(newExtractorLink(name, label, url, type) {
                this.referer = episodeUrl
                this.headers = browserHeaders + ("Referer" to episodeUrl)
                this.quality = Qualities.Unknown.value
            })
            emitted = true
        }

        iframeCandidates.forEach { (iframeUrl, label) ->
            val resolved = mutableListOf<ExtractorLink>()
            if (SelcukFlixContentXExtractor.canHandle(iframeUrl)) {
                // The known Pichive flow needs the site origin on iframe.php and the iframe
                // itself as Referer for source2.php. The custom extractor owns that exact sequence.
                runCatching {
                    SelcukFlixContentXExtractor().getUrl(
                        iframeUrl,
                        episodeUrl,
                        subtitleCallback
                    ) { link ->
                        if (!SelcukFlixResolverBank.isYouTubeUrl(link.url)) resolved += link
                    }
                }.onFailure {
                    android.util.Log.w("SelcukFlix", "Pichive resolver failed; trying the registered extractor.")
                }
            }

            // Other player hosts use CloudStream's maintained extractor registry. For Pichive,
            // this is a fallback only after the observed site-specific flow produced no link.
            if (resolved.isEmpty()) {
                runCatching {
                    loadExtractor(
                        iframeUrl,
                        episodeUrl,
                        { sub ->
                            subtitleCallback(
                                SubtitleFile(sub.lang, sub.url).apply {
                                    headers = sub.headers ?: mapOf("Referer" to iframeUrl)
                                }
                            )
                        }
                    ) { link ->
                        if (!SelcukFlixResolverBank.isYouTubeUrl(link.url)) resolved += link
                    }
                }.onFailure {
                    android.util.Log.d("SelcukFlix", "No registered extractor resolved an observed player.")
                }
            }

            resolved.distinctBy { it.url + "|" + it.type + "|" + it.referer }.forEach { link ->
                callback(newExtractorLink(name, "$label → " + link.name, link.url, link.type) {
                    this.referer = link.referer.ifBlank { iframeUrl }
                    this.headers = link.headers ?: (browserHeaders + ("Referer" to iframeUrl))
                    this.quality = link.quality
                    this.extractorData = link.extractorData
                })
                emitted = true
            }
        }

        android.util.Log.i(
            "SelcukFlix",
            "Kaynak çözümleme tamamlandı: " + emitted + "; iframe=" +
                iframeCandidates.size + ", direct=" + directCandidates.size
        )
        return emitted
    }
}

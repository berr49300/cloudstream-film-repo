package com.kra.cloudstream.diziyou

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DiziYou : MainAPI() {
    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    // Match the working archive URLs used by other public DiziYou implementations.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",
        "$mainUrl/dizi-arsivi/?filtrele=alfabetik&sirala=ASC" to "Dizi Arşivi",
        "$mainUrl/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7" to "IMDb 7+"
    )

    private fun Element.absHref(): String? =
        fixUrlNull(attr("href"))?.takeIf { it.startsWith("http") }

    private fun Element.posterUrl(): String? {
        // A card may contain a language badge/flag before the real poster.
        val preferred = listOf(
            "div#categorytitle img", "div.category_image img", "div.cat-img-main img",
            ".series-poster img", ".afis img", ".poster img", ".cover img",
            "img.poster", "img.series-poster"
        ).flatMap { select(it) }
        val candidates = (preferred + select("img")).distinct()
        for (img in candidates) {
            val alt = img.attr("alt").ifBlank { img.attr("title") }.lowercase()
            val attributes = listOf(
                "data-poster", "data-original", "data-lazy-src", "data-src",
                "data-lazyload", "data-lazy", "data-original-src", "data-srcset",
                "srcset", "src"
            )
            val values = attributes.mapNotNull { key ->
                val raw = img.attr(key).trim()
                if (raw.isBlank() || raw.startsWith("data:image", true)) null
                else if (key.endsWith("srcset")) {
                    raw.split(",").map { entry ->
                        val item = entry.trim()
                        item.substringBefore(" ") to (Regex("""(\d+)(?:w|x)""").find(item)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0)
                    }.maxByOrNull { it.second }?.first?.takeIf { it.isNotBlank() }
                } else raw
            }
            val languageBadge = alt in setOf(
                "dublaj", "türkçe dublaj", "turkce dublaj", "altyazılı", "altyazili",
                "ingilizce altyazılı", "ingilizce altyazili", "english subtitle", "tr", "en"
            ) || img.classNames().any { it.lowercase() in setOf("flag", "language-flag", "lang-flag") }
            val poster = values.firstOrNull { value ->
                val lower = value.lowercase()
                !listOf(
                    "placeholder", "no-image", "noimage", "default-poster", "transparent",
                    "blank.gif", "loading.svg", "bayrak", "/flags/", "flag-icon", "turkish-flag"
                ).any(lower::contains) && !languageBadge
            }?.let { fixUrlNull(it) }
            if (poster != null && !poster.startsWith("data:", true)) return poster
        }
        return null
    }
    private fun isSeriesPath(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val expected = runCatching { URI(mainUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        val host = uri.host.orEmpty().removePrefix("www.")
        if (expected.isBlank() || !host.equals(expected, ignoreCase = true)) return false
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isBlank() || path == "/" ||
            Regex("""-\d+-sezon-\d+-bolum$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
        return !path.startsWith("/wp-", true) &&
            !path.startsWith("/kategori/", true) &&
            !path.startsWith("/category/", true) &&
            !path.startsWith("/tag/", true) &&
            !path.contains("/page/", true) &&
            path !in setOf("/genel", "/hakkimizda", "/iletisim", "/dizi-arsivi", "/search")
    }

    private fun cleanSeriesTitle(value: String?): String? {
        val cleaned = value.orEmpty()
            .replace(Regex("""(?i)\s*[-|]\s*DiziYou\s*$"""), "")
            .replace(Regex("""(?i)\s+izle\s*$"""), "")
            .replace(Regex("""(?i)\s+\(?\d{4}\)?\s*$"""), "")
            .replace(Regex("""(?i)\s+\d+\.?\s*sezon\b.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\.?\s*b[öo]l[üu]m\b.*$"""), "")
            .replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
        val lower = cleaned.lowercase()
        if (cleaned.isBlank() || lower in setOf("yok", "bilgi", "oynat", "izleniyor", "son eklenen bölümler", "dizi arşivi")) return null
        if (listOf("altyazılı", "altyazili", "dublaj", "önceki bölüm", "onceki bolum").any(lower::contains)) return null
        return cleaned
    }

    private fun slugTitle(url: String): String? {
        val slug = runCatching { URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/') }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val root = slug.replace(Regex("""-(\d+)-sezon-(\d+)-bolum$""", RegexOption.IGNORE_CASE), "")
        if (root.isBlank()) return null
        return root.split('-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } }
    }

    private fun Element.resultTitle(): String {
        val selectors = listOf("#categorytitle a", "#categorytitle", ".cat-title-main span",
            ".cat-title-main a", ".series-title", ".entry-title", ".post-title", "h1", "h2", "h3", "h4")
        val values = mutableListOf<String>()
        selectors.forEach { selectFirst(it)?.text()?.takeIf(String::isNotBlank)?.let(values::add) }
        attr("title").takeIf(String::isNotBlank)?.let(values::add)
        selectFirst("a[title]")?.attr("title")?.takeIf(String::isNotBlank)?.let(values::add)
        selectFirst("img")?.attr("alt")?.takeIf(String::isNotBlank)?.let(values::add)
        return values.asSequence().mapNotNull(::cleanSeriesTitle).firstOrNull().orEmpty()
    }

    private fun Element.toSeriesResult(): SearchResponse? {
        val anchor = if (tagName() == "a" && hasAttr("href")) this else
            select("a[href]").firstOrNull { it.absHref()?.let(::isSeriesPath) == true } ?: return null
        val href = anchor.absHref() ?: return null
        if (!isSeriesPath(href)) return null
        val title = sequenceOf(resultTitle(), anchor.attr("title"), anchor.selectFirst("img")?.attr("alt"),
            anchor.text(), slugTitle(href)).mapNotNull(::cleanSeriesTitle).firstOrNull() ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = this@toSeriesResult.posterUrl() ?: anchor.posterUrl()
        }
    }

    private fun Element.toSeriesFromEpisode(): SearchResponse? {
        val episodeUrl = absHref() ?: return null
        val seriesUrl = seriesUrlFromEpisode(episodeUrl, mainUrl) ?: return null
        val candidates = listOf("#dizi-ismi", "[data-series-title]", ".cat-title-main span",
            ".cat-title-main a", ".series-title", ".entry-title", "h1", "h2", "h3", "h4")
            .mapNotNull { selectFirst(it)?.text()?.takeIf(String::isNotBlank) } +
            listOf(attr("title"), selectFirst("a[title]")?.attr("title").orEmpty())
        // Artwork alt text may be "İngilizce Altyazılı" / "Türkçe Dublaj", not the series name.
        val title = candidates.asSequence().mapNotNull(::cleanSeriesTitle).firstOrNull()
            ?: slugTitle(seriesUrl)?.let(::cleanSeriesTitle) ?: return null
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = this@toSeriesFromEpisode.posterUrl()
        }
    }

    private fun cards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select(".listepisodes a[href], div.bolumust a[href], .latest-episodes a[href], a[href*='-sezon-'][href*='-bolum']")
            .forEach { it.toSeriesFromEpisode()?.let(results::add) }

        // Parse each card/link, not the #list-series wrapper as one item.
        val selectors = listOf(
            "div#list-series-main", "div#list-series-main a[href]", "div.single-item", "div.single-item a[href]",
            "div#list-series a[href]", ".list-series a[href]", ".alphabetical-category-wrapper a[href]",
            "div.incontent a[href]", "div.cat-item", "div.cat-item a[href]", "div.series-item",
            "div.series-item a[href]", "div.post", "div.post a[href]", "div.search-result",
            "div.search-result a[href]", ".post-title a[href]", ".entry-title a[href]", "article a[href]"
        )
        selectors.forEach { selector -> doc.select(selector).forEach { it.toSeriesResult()?.let(results::add) } }
        return results.distinctBy { it.url }
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val parts = base.split("?", limit = 2)
        val path = parts[0].trimEnd('/')
        val paged = "$path/page/$page/"
        return if (parts.size == 2) "$paged?${parts[1]}" else paged
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val doc = app.get(url, referer = "$mainUrl/").document
        val results = cards(doc).take(60)
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/page/2/?s=$encoded")
        return urls.flatMap { url ->
            runCatching { cards(app.get(url, referer = "$mainUrl/").document) }
                .getOrDefault(emptyList())
        }.distinctBy { it.url }.take(60)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // A latest-episode card must open its series, not expose the episode page as a series.
        val canonical = seriesUrlFromEpisode(url, mainUrl)
        var resolvedUrl = canonical ?: url
        var activeDoc = runCatching { app.get(resolvedUrl, referer = "$mainUrl/").document }.getOrNull()
        if (activeDoc == null || (canonical != null && extractEpisodes(activeDoc, resolvedUrl).isEmpty())) {
            runCatching { app.get(url, referer = "$mainUrl/").document }.getOrNull()?.let {
                activeDoc = it
                resolvedUrl = url
            }
        }
        val page = activeDoc ?: return null
        val episodes = extractEpisodes(page, resolvedUrl).ifEmpty {
            if (isEpisodeUrl(url)) {
                val nums = parseEpisodeNumbers(url)
                listOf(newEpisode(url) {
                    name = if (nums.first != null && nums.second != null) "${nums.first}. Sezon ${nums.second}. Bölüm" else "Bölüm"
                    season = nums.first ?: 1
                    episode = nums.second
                })
            } else emptyList()
        }

        val rawTitle = page.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: page.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
            ?: slugTitle(resolvedUrl) ?: return null
        val title = cleanSeriesTitle(rawTitle) ?: rawTitle
        val poster = page.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { fixUrlNull(it) }
            ?: page.selectFirst("div.category_image, div.cat-img-main, .series-poster, .afis, .poster")?.posterUrl()
            ?: page.selectFirst("div.category_image img, div.cat-img-main img, .series-poster img")?.parent()?.posterUrl()
            ?: page.selectFirst("main, article, body")?.posterUrl()
        val plot = page.selectFirst("div.diziyou_desc")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: page.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.takeIf(String::isNotBlank)
        val year = Regex("""\b(19|20)\d{2}\b""").find(page.text())?.value?.toIntOrNull()
        val tags = page.select("div.genres a, a[href*='/kategori/'], a[href*='/tur/']")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()

        return newTvSeriesLoadResponse(title, canonical ?: resolvedUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun isEpisodeUrl(url: String): Boolean =
        Regex("""-\d+-sezon-\d+-bolum/?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault(""))

    private fun extractEpisodes(doc: Document, seriesUrl: String): List<Episode> {
        val seriesPath = runCatching { URI(seriesUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absHref() ?: return@mapNotNull null
            val parent = seriesUrlFromEpisode(href, mainUrl) ?: return@mapNotNull null
            val parentPath = runCatching { URI(parent).path.orEmpty().trimEnd('/') }.getOrDefault("")
            if (!parentPath.equals(seriesPath, ignoreCase = true)) return@mapNotNull null

            val nums = parseEpisodeNumbers(href)
            val visible = sequenceOf(anchor.selectFirst("div.bolumismi")?.text(),
                anchor.selectFirst("div.baslik")?.ownText(), anchor.selectFirst("div.baslik")?.text(), anchor.text())
                .filterNotNull().map { it.trim().replace(Regex("""\s+"""), " ") }
                .firstOrNull { it.isNotBlank() && !isGenericEpisodeLabel(it) }.orEmpty()
            val textNums = parseEpisodeNumbers(visible)
            val seasonNo = nums.first ?: textNums.first ?: 1
            val episodeNo = nums.second ?: textNums.second ?: return@mapNotNull null
            val episodeName = cleanDiziYouEpisodeName(visible, seasonNo, episodeNo)
            newEpisode(href) {
                name = episodeName
                season = seasonNo
                episode = episodeNo
                posterUrl = anchor.posterUrl()
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.name }))
    }

    private fun isGenericEpisodeLabel(value: String): Boolean {
        val text = value.lowercase().replace(Regex("""\s+"""), " ").trim()
        return text.isBlank() || text.contains("son eklenen bölümler") ||
            text.contains("önceki bölüm") || text.contains("onceki bolum") ||
            text == "dizi arşivi" || text == "oynat"
    }

    private fun playerId(doc: Document): String? {
        val frame = doc.selectFirst("iframe#diziyouPlayer, iframe[src*=player], iframe[data-src*=player]")
        val src = frame?.attr("data-src").orEmpty().ifBlank { frame?.attr("src").orEmpty() }
        return Regex("/player/([a-zA-Z0-9_-]+)(?:\\.html)?", RegexOption.IGNORE_CASE)
            .find(src)?.groupValues?.getOrNull(1)
            ?: Regex("[?&](?:id|itemId)=([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(src)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)\bitemId\s*[:=]\s*['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)\bdata-id\s*=\s*['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
    }

    private fun hasOption(doc: Document, vararg ids: String): Boolean =
        ids.any { id ->
            doc.selectFirst("#$id, [id=$id], [data-id=$id], [class~=$id]") != null ||
                doc.html().contains(id, ignoreCase = true)
        }

    private fun playerFrameUrl(doc: Document): String? {
        val frame = doc.selectFirst("iframe#diziyouPlayer, iframe[src*=player], iframe[data-src*=player]")
            ?: doc.selectFirst("iframe[src], iframe[data-src]")
            ?: return null
        val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
        return src.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun directMediaType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url).lowercase()
        return when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDoc = app.get(data, referer = "$mainUrl/").document
        val playerUrl = playerFrameUrl(episodeDoc)
        val id = playerUrl?.let {
            Regex("""/player/([a-zA-Z0-9_-]+)(?:\.html)?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        } ?: playerId(episodeDoc)

        var emitted = false
        var foundNativeMedia = false

        // Prefer URLs actually exposed by the player page; do not rely on a guessed URL
        // when the live player provides a concrete source.
        if (playerUrl != null) {
            runCatching {
                val playerDoc = app.get(playerUrl, referer = data).document
                // Prefer the one source actually selected by the player. Treating every URL
                // found in scripts/iframes as independent media creates duplicate dead links.
                parseDiziYouMediaUrls(playerDoc).firstOrNull()?.let { source ->
                    val mediaType = directMediaType(source)
                    if (mediaType != null) {
                        val quality = explicitQualityFromUrl(source)
                        val qualityLabel = if (quality > 0) " • ${quality}p" else ""
                        callback(newExtractorLink(name, "DiziYou • Native$qualityLabel", source, mediaType) {
                            referer = playerUrl
                            this.quality = if (quality > 0) quality else Qualities.Unknown.value
                        })
                        emitted = true
                        foundNativeMedia = true
                    }
                }
                playerDoc.select("track[src], track[data-src]").forEach { track ->
                    val src = track.attr("src").ifBlank { track.attr("data-src") }
                    val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Altyazı" }
                    if (src.isNotBlank()) subtitleCallback(SubtitleFile(lang, fixUrl(src)))
                }
            }
        }

        // Compatibility fallback for the known DiziYou storage layout. Only advertise
        // language variants when the episode page actually declares those options.
        if (!id.isNullOrBlank()) {
            val trSub = hasOption(episodeDoc, "turkceAltyazili", "turkceAltyaziliOption")
            val enSub = hasOption(episodeDoc, "ingilizceAltyazili", "ingilizceAltyaziliOption")
            val trDub = hasOption(episodeDoc, "turkceDublaj", "turkceDublajOption")
            val storage = "https://storage.diziyou.one"
            if (!foundNativeMedia) {
                callback(newExtractorLink(name, "DiziYou • Orijinal Dil", "$storage/episodes/$id/play.m3u8", ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                })
                emitted = true
            }
            if (trSub) subtitleCallback(SubtitleFile("Türkçe", "$storage/subtitles/$id/tr.vtt"))
            if (enSub) subtitleCallback(SubtitleFile("İngilizce", "$storage/subtitles/$id/en.vtt"))
            if (trDub) {
                callback(newExtractorLink(name, "DiziYou • Türkçe Dublaj", "$storage/episodes/${id}_tr/play.m3u8", ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                })
                emitted = true
            }
        }

        // Some episode pages expose a usable embed directly instead of the named player.
        // If a named player exists, do not parse its iframe a second time from the episode page.
        if (playerUrl == null) {
            parseDiziYouSourceUrls(episodeDoc).firstOrNull()?.let { source ->
                runCatching {
                    val mediaType = directMediaType(source)
                    if (mediaType != null) {
                        val quality = explicitQualityFromUrl(source)
                        val qualityLabel = if (quality > 0) " • ${quality}p" else ""
                        callback(newExtractorLink(name, "DiziYou • Native$qualityLabel", source, mediaType) {
                            referer = data
                            this.quality = if (quality > 0) quality else Qualities.Unknown.value
                        })
                        emitted = true
                    } else {
                        loadExtractor(source, data, subtitleCallback, { link ->
                            callback(link)
                            emitted = true
                        })
                    }
                }
            }
        }
        return emitted
    }
}


internal fun seriesUrlFromEpisode(
    episodeUrl: String,
    mainUrl: String = "https://www.diziyou.one"
): String? {
    val uri = runCatching { URI(episodeUrl) }.getOrNull() ?: return null
    val expectedHost = runCatching { URI(mainUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    val actualHost = uri.host.orEmpty().removePrefix("www.")
    if (expectedHost.isBlank() || !actualHost.equals(expectedHost, ignoreCase = true)) return null
    val path = uri.path.orEmpty().trimEnd('/')
    val seriesPath = path.replace(Regex("""-(\d+)-sezon-(\d+)-bolum$""", RegexOption.IGNORE_CASE), "")
    if (seriesPath == path || seriesPath.isBlank() || seriesPath == "/") return null
    return mainUrl.trimEnd('/') + seriesPath + "/"
}

private fun explicitQualityFromUrl(url: String): Int {
    return Regex("""(?i)(2160|1440|1080|720|576|540|480|360)p?""")
        .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
}

private fun directMediaUrl(url: String): Boolean {
    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url).lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd") ||
        path.endsWith(".mp4") || path.endsWith(".m4v")
}

/**
 * Prefer the specifically selected #diziyouSource, then actual HTML5 source elements,
 * and only then a single URL discovered in player JavaScript. Never emit all guessed URLs.
 */
internal fun parseDiziYouMediaUrls(doc: Document): List<String> {
    fun mediaUrl(el: Element): String? {
        val key = if (el.attr("data-src").isNotBlank()) "data-src" else "src"
        val raw = el.attr(key).trim()
        if (raw.isBlank() || raw.startsWith("data:", true)) return null
        val absolute = el.absUrl(key)
        val value = absolute.takeIf { it.isNotBlank() }
            ?: if (raw.startsWith("http", true)) raw else "https://www.diziyou.one/" + raw.trimStart('/')
        return value.takeIf(::directMediaUrl)
    }

    val chosen = doc.select("#diziyouSource[src], #diziyouSource[data-src]")
        .mapNotNull(::mediaUrl).distinct()
    if (chosen.isNotEmpty()) return chosen.take(1)

    val html5 = doc.select("video source[src], video source[data-src], video[src], video[data-src]")
        .mapNotNull(::mediaUrl).distinct()
    if (html5.isNotEmpty()) return html5.take(1)

    val fromScript = Regex("""(?i)(https?:(?:\\?/){2}[^"'\s<>]+?\.(?:m3u8|mp4|m4v|mpd)(?:\?[^"'\s<>]*)?)""")
        .findAll(doc.html())
        .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
        .filter(::directMediaUrl)
        .distinct()
        .toList()
    return fromScript.take(1)
}

internal fun parseDiziYouSourceUrls(doc: Document): List<String> = buildList {
    doc.select("video source[src], video source[data-src], source[src], source[data-src], video[src], video[data-src], #diziyouSource[src], #diziyouSource[data-src], iframe[src], iframe[data-src]").forEach { el ->
        val key = if (el.attr("data-src").isNotBlank()) "data-src" else "src"
        val raw = el.attr(key).trim()
        if (raw.isNotBlank()) {
            val absolute = el.absUrl(key)
            add(absolute.takeIf { it.isNotBlank() } ?: if (raw.startsWith("http")) raw else "https://www.diziyou.one/" + raw.trimStart('/'))
        }
    }
    Regex("""(?i)(https?:(?:\\?/){2}[^"'\s<>]+?\.(?:m3u8|mp4|m4v|mpd)(?:\?[^"'\s<>]*)?)""")
        .findAll(doc.html()).forEach { match ->
            add(match.value.replace("\\/", "/").replace("&amp;", "&"))
        }
}.distinct()

internal fun cleanDiziYouEpisodeName(label: String, season: Int, episode: Int): String {
    var candidate = label.trim().replace(Regex("""\s+"""), " ")
    // Older pages often show only a filename, e.g. "(eps1.0_hellofriend.mov)".
    candidate = candidate
        .replace(Regex("""(?i)\(?\s*eps\d+(?:[._-]\d+)?[_-][a-z0-9_-]+\.(?:mov|mpeg|mp4|mkv|wmv|asf|flv|avi|qt|m4v)\s*\)?"""), " ")
        .replace(Regex("""(?i)\(?\s*[a-z0-9_-]+\.(?:mov|mpeg|mp4|mkv|wmv|asf|flv|avi|qt|m4v)\s*\)?"""), " ")
        .replace(Regex("""(?i)^\s*\d+\.?\s*sezon\s*"""), "")
        .replace(Regex("""(?i)^\s*\d+\.?\s*b[öo]l[üu]m\s*"""), "")
        .replace(Regex("""^\s*\d+\s*[.)-]\s*"""), "")
        .replace(Regex("""[()\[\]]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':', '.', '•')
    val generic = "$season. Sezon $episode. Bölüm"
    if (candidate.isBlank() || candidate.equals("oynat", true) ||
        candidate.contains("son eklenen bölümler", true) ||
        candidate.contains("önceki bölüm", true) || candidate.contains("onceki bolum", true)
    ) return generic
    return "$generic — $candidate"
}

internal fun parseEpisodeNumbers(label: String): Pair<Int?, Int?> {
    val season = Regex("(?i)(\\d+)\\.\\s*sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = Regex("(?i)(\\d+)\\.\\s*(?:bölüm|bolum)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-bolum").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return season to episode
}


internal fun seriesTitleFromEpisodeUrl(episodeUrl: String, mainUrl: String = "https://www.diziyou.one"): String? =
    seriesUrlFromEpisode(episodeUrl, mainUrl)?.let { url ->
        URI(url).path.orEmpty().trimEnd('/').substringAfterLast('/')
            .split('-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } }
    }

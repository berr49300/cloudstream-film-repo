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
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popüler Dizilerden Son Bölümler",
        "$mainUrl/genel/" to "Dizi Arşivi",
        "$mainUrl/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",
        "$mainUrl/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7" to "IMDb 7+"
    )

    private fun Element.absHref(): String? =
        fixUrlNull(attr("href"))?.takeIf { it.startsWith("http") }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img") ?: return null
        return listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { key ->
                img.attr(key).trim().takeIf { it.isNotBlank() && !it.startsWith("data:image", true) }
            }?.let { fixUrlNull(it) }
    }

    private fun Element.resultTitle(): String {
        val imageAlt = selectFirst("img")?.attr("alt").orEmpty()
        return listOf(
            selectFirst("#categorytitle a")?.text(),
            selectFirst(".cat-title-main span")?.text(),
            selectFirst(".cat-title-main a")?.text(),
            selectFirst(".cat-title-main")?.text(),
            selectFirst("h2, h3, h4, [class*=title]")?.text(),
            imageAlt,
            attr("title")
        ).map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }
            .orEmpty().replace(Regex("\\s+"), " ")
    }

    private fun Element.isSeriesCard(): Boolean {
        val href = absHref() ?: return false
        val uri = runCatching { URI(href) }.getOrNull() ?: return false
        if (!uri.host.equals(URI(mainUrl).host, true)) return false
        val path = uri.path.orEmpty()
        if (path.isBlank() || path == "/" || path.startsWith("/wp-") ||
            path.contains("-sezon-") || path.contains("-bolum") ||
            path in setOf("/genel/", "/hakkimizda/", "/iletisim/")) return false
        return selectFirst("img") != null && resultTitle().isNotBlank()
    }

    private fun Element.toSeriesResult(): SearchResponse? {
        if (!isSeriesCard()) return null
        val href = absHref() ?: return null
        val title = resultTitle().removeSuffix(" izle").trim()
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = this@toSeriesResult.posterUrl()
        }
    }

    internal fun seriesUrlFromEpisode(episodeUrl: String): String? {
        val uri = runCatching { URI(episodeUrl) }.getOrNull() ?: return null
        if (!uri.host.equals(URI(mainUrl).host, ignoreCase = true)) return null
        val path = uri.path.orEmpty().trimEnd('/')
        val seriesPath = path.replace(Regex("""-(\d+)-sezon-(\d+)-bolum$""", RegexOption.IGNORE_CASE), "")
        if (seriesPath == path || seriesPath.isBlank() || seriesPath == "/") return null
        return "$mainUrl$seriesPath/"
    }

    private fun Element.toSeriesFromEpisode(): SearchResponse? {
        val episodeUrl = absHref() ?: return null
        val seriesUrl = seriesUrlFromEpisode(episodeUrl) ?: return null
        val rawTitle = selectFirst("#dizi-ismi, .cat-title-main, [data-series-title]")?.text()
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("aria-label").trim().takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
        val slugTitle = runCatching { URI(seriesUrl).path.orEmpty().trim('/') }
            .getOrNull()?.substringAfterLast('/')?.replace('-', ' ')
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val title = (rawTitle ?: slugTitle).orEmpty()
            .replace(Regex("""(?i)\s+\d+\.?\s*sezon.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\.?\s*b[öo]l[üu]m.*$"""), "")
            .trim()
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = this@toSeriesFromEpisode.posterUrl()
        }
    }

    private fun cards(doc: Document): List<SearchResponse> {
        // The homepage/search may expose latest episode cards rather than series cards.
        // Convert only URLs matching the site's explicit season/episode slug pattern.
        val episodeCards = doc.select(".listepisodes a[href], div.bolumust a[href], .latest-episodes a[href]")
            .mapNotNull { it.toSeriesFromEpisode() }
            .distinctBy { it.url }
        val selectors = listOf(
            "div.incontent div#list-series",
            "div#list-series-main",
            "div.cat-img-main",
            "article",
            ".site-content a[href]",
            "main a[href]"
        )
        for (selector in selectors) {
            val found = doc.select(selector).mapNotNull { el ->
                val card = if (el.tagName() == "a" && el.hasAttr("href")) el else el.selectFirst("a[href]") ?: el
                card.toSeriesResult() ?: el.toSeriesResult()
            }.distinctBy { it.url }
            if (found.isNotEmpty()) return (episodeCards + found).distinctBy { it.url }
        }
        return episodeCards
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
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = doc.selectFirst("div.category_image img")?.let { img ->
            listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }
            }?.let { fixUrlNull(it) }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst("div.diziyou_desc")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.text())?.value?.toIntOrNull()
        val tags = doc.select("div.genres a").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodes = doc.select("div.bolumust").mapNotNull { el ->
            val anchor = el.closest("a[href]") ?: el.selectFirst("a[href]") ?: return@mapNotNull null
            val episodeUrl = anchor.absHref() ?: return@mapNotNull null
            val label = el.selectFirst("div.baslik")?.ownText()?.trim()
                ?.ifBlank { el.selectFirst("div.baslik")?.text()?.trim().orEmpty() }.orEmpty()
            val name = el.selectFirst("div.bolumismi")?.text()?.trim()
                ?.replace(Regex("[()]"), "")?.trim()?.takeIf { it.isNotBlank() }
                ?: label.ifBlank { anchor.text().trim() }
            val numbers = parseEpisodeNumbers("$label $name $episodeUrl")
            if (numbers.second == null && !episodeUrl.contains(Regex("(?i)-\\d+-sezon-\\d+-bolum"))) return@mapNotNull null
            newEpisode(episodeUrl) {
                this.name = name.ifBlank { "Bölüm ${numbers.second ?: ""}" }
                season = numbers.first ?: 1
                episode = numbers.second
                posterUrl = el.selectFirst("img")?.let { img ->
                    listOf("data-src", "src").firstNotNullOfOrNull { k -> img.attr(k).takeIf { it.isNotBlank() } }
                        ?.let { fixUrlNull(it) }
                }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.name }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
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

    internal fun sourceUrls(doc: Document): List<String> = buildList {
        doc.select("video source[src], source[src], video[src], iframe[src], iframe[data-src]").forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("src") }.trim()
            if (src.isNotBlank()) add(fixUrl(src))
        }
        // Player configs may store URLs as ordinary https://... or escaped https:\/\/...
        Regex("""(?i)(https?:(?:\\?/){2}[^"'\s<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^"'\s<>]*)?)""")
            .findAll(doc.html()).forEach { match ->
                add(match.value.replace("\\/", "/").replace("&amp;", "&"))
            }
    }.distinct()

    private fun playerFrameUrl(doc: Document): String? {
        val frame = doc.selectFirst("iframe#diziyouPlayer, iframe[src*=player], iframe[data-src*=player]")
            ?: doc.selectFirst("iframe[src], iframe[data-src]")
            ?: return null
        val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
        return src.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
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
                sourceUrls(playerDoc).forEach { source ->
                    when {
                        source.contains(".m3u8", ignoreCase = true) -> {
                            callback(newExtractorLink(name, "DiziYou • Player", source, ExtractorLinkType.M3U8) {
                                referer = playerUrl
                                quality = Qualities.Unknown.value
                            })
                            emitted = true
                            foundNativeMedia = true
                        }
                        source.contains(".mp4", ignoreCase = true) || source.contains(".mpd", ignoreCase = true) -> {
                            runCatching { loadExtractor(source, playerUrl, subtitleCallback, callback) }
                                .onSuccess { emitted = true }
                        }
                        else -> runCatching {
                            loadExtractor(source, playerUrl, subtitleCallback, callback)
                        }.onSuccess { emitted = true }
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
            val html = episodeDoc.html()
            val trSub = hasOption(episodeDoc, "turkceAltyazili", "turkceAltyaziliOption")
            val enSub = hasOption(episodeDoc, "ingilizceAltyazili", "ingilizceAltyaziliOption")
            val trDub = hasOption(episodeDoc, "turkceDublaj", "turkceDublajOption")
            val storage = "https://storage.diziyou.one"
            if (!foundNativeMedia && html.contains("diziyouPlayer", ignoreCase = true)) {
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
        sourceUrls(episodeDoc).forEach { source ->
            if (source == playerUrl) return@forEach
            runCatching {
                if (source.contains(".m3u8", ignoreCase = true)) {
                    callback(newExtractorLink(name, "DiziYou • Video", source, ExtractorLinkType.M3U8) {
                        referer = data
                        quality = Qualities.Unknown.value
                    })
                    emitted = true
                } else {
                    loadExtractor(source, data, subtitleCallback, callback)
                    emitted = true
                }
            }
        }
        return emitted
    }
}

internal fun parseEpisodeNumbers(label: String): Pair<Int?, Int?> {
    val season = Regex("(?i)(\\d+)\\.\\s*sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = Regex("(?i)(\\d+)\\.\\s*(?:bölüm|bolum)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-bolum").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return season to episode
}

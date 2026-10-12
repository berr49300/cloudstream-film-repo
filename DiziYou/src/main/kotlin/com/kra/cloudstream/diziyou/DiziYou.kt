package com.kra.cloudstream.diziyou

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

/** Conservative provider; the live raw DOM and player contract are still partly unverified. */
class DiziYou : MainAPI() {
    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/genel/" to "Dizi Arşivi",
        "$mainUrl/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",
        "$mainUrl/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7" to "IMDb 7+"
    )

    private fun Element.absoluteLink(): String? = fixUrlNull(attr("href"))?.takeIf { it.startsWith("http") }
    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null
        return listOf("data-src", "data-lazy-src", "data-original", "src").asSequence()
            .map { img.attr(it).trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:image", true) }?.let { fixUrlNull(it) }
    }
    private fun Element.cardTitle(): String = listOf(selectFirst("h2, h3, h4, [class*=title]")?.text(), selectFirst("img")?.attr("alt"), attr("title"), text())
        .map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("\\s+"), " ")
    private fun Element.isSeriesCandidate(): Boolean {
        val href = absoluteLink() ?: return false
        val uri = runCatching { URI(href) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        if (!uri.host.equals(URI(mainUrl).host, true)) return false
        if (path.isBlank() || path == "/" || path.startsWith("/wp-") || path.contains("-sezon-") || path.contains("-bolum") || path in setOf("/genel/", "/hakkimizda/", "/iletisim/")) return false
        return selectFirst("img") != null && cardTitle().isNotBlank()
    }
    private fun Element.toSearchResponse(): SearchResponse? {
        if (!isSeriesCandidate()) return null
        val href = absoluteLink() ?: return null
        val title = cardTitle().removeSuffix(" izle").trim()
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = this@toSearchResponse.poster() }
    }
    private fun cards(doc: org.jsoup.nodes.Document) = doc.select("main a[href], #content a[href], article a[href], .site-content a[href], a[href]")
        .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val parts = base.split("?", limit = 2)
        val path = parts[0].trimEnd('/')
        val paged = "$path/page/$page/"
        return if (parts.size == 2) "$paged?${parts[1]}" else paged
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val results = cards(app.get(url, referer = "$mainUrl/").document).take(60)
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/page/2/?s=$encoded")
        return urls.flatMap { url ->
            runCatching { cards(app.get(url, referer = "$mainUrl/").document) }.getOrDefault(emptyList())
        }.distinctBy { it.url }.take(60)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("DiziYou: başlık bulunamadı")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("article img, .site-content img, main img")?.let { img ->
                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { k -> img.attr(k).takeIf { it.isNotBlank() } }?.let { fixUrlNull(it) }
            }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[class*=description], [class*=synopsis], .entry-content")?.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.text())?.value?.toIntOrNull()
        val episodes = doc.select("a[href]").mapNotNull { a ->
            val href = a.absoluteLink() ?: return@mapNotNull null
            if (!runCatching { URI(href).host.equals(URI(mainUrl).host, true) }.getOrDefault(false)) return@mapNotNull null
            val label = a.text().trim().replace(Regex("\\s+"), " ")
            val numbering = parseEpisodeNumbers("$label $href")
            if (numbering.second == null && !label.contains("bölüm", true) && !label.contains("bolum", true)) return@mapNotNull null
            val card = a.closest(".episode, .bolumust, article, li, [class*=episode]") ?: a
            newEpisode(href) {
                name = label.ifBlank { "Bölüm" }
                season = numbering.first ?: 1
                episode = numbering.second
                posterUrl = card.selectFirst("img")?.let { img -> listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { k -> img.attr(k).takeIf { it.isNotBlank() } }?.let { fixUrlNull(it) } }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.name }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; this.plot = plot; this.year = year
        }
    }
    internal fun extractPlayerId(doc: org.jsoup.nodes.Document): String? {
        val frame = doc.selectFirst("iframe#diziyouPlayer, iframe[src*=player], iframe[data-src*=player]")
        val frameUrl = frame?.attr("data-src").orEmpty().ifBlank { frame?.attr("src").orEmpty() }
        return Regex("/player/([a-zA-Z0-9_-]+)(?:\\.html)?", RegexOption.IGNORE_CASE)
            .find(frameUrl)?.groupValues?.getOrNull(1)
            ?: Regex("[?&](?:id|itemId)=([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(frameUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)\bitemId\s*[:=]\s*['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)\bdata-id\s*=\s*['"]([a-zA-Z0-9_-]+)['"]""").find(doc.html())?.groupValues?.getOrNull(1)
    }

    private fun hasOption(doc: org.jsoup.nodes.Document, vararg ids: String): Boolean =
        ids.any { doc.selectFirst("#$it, [id=$it], [data-id=$it]") != null }

    private suspend fun emitNativeCandidates(
        doc: org.jsoup.nodes.Document,
        itemId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (itemId.isNullOrBlank()) return false
        val trSub = hasOption(doc, "turkceAltyazili", "turkceAltyaziliOption")
        val enSub = hasOption(doc, "ingilizceAltyazili", "ingilizceAltyaziliOption")
        val trDub = hasOption(doc, "turkceDublaj", "turkceDublajOption")
        if (!trSub && !enSub && !trDub) return false
        val storage = "https://storage.diziyou.one"
        fun emit(label: String, url: String) {
            callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
            })
        }
        if (trSub || enSub) emit("DiziYou • Orijinal Dil", "$storage/episodes/$itemId/play.m3u8")
        if (trSub) subtitleCallback(SubtitleFile("Türkçe", "$storage/subtitles/$itemId/tr.vtt"))
        if (enSub) subtitleCallback(SubtitleFile("İngilizce", "$storage/subtitles/$itemId/en.vtt"))
        if (trDub) emit("DiziYou • Türkçe Dublaj", "$storage/episodes/${itemId}_tr/play.m3u8")
        return true
    }

    internal fun getPlayerCandidates(doc: org.jsoup.nodes.Document): List<String> =
        doc.select("iframe[src], iframe[data-src], video source[src], source[src]").mapNotNull { el ->
            listOf("data-src", "src").firstNotNullOfOrNull { key ->
                el.attr(key).trim().takeIf { it.isNotBlank() }
            }?.let { fixUrlNull(it) }
        }.distinct()

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        var found = emitNativeCandidates(doc, extractPlayerId(doc), subtitleCallback, callback)
        getPlayerCandidates(doc).forEach { frame ->
            runCatching {
                loadExtractor(frame, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}

/** Extracts season/episode numbering from observed labels such as "2. Sezon 3. Bölüm". */
internal fun parseEpisodeNumbers(label: String): Pair<Int?, Int?> {
    val season = Regex("(?i)(\\d+)\\.\\s*sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = Regex("(?i)(\\d+)\\.\\s*(?:bölüm|bolum)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(\\d+)-bolum").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return season to episode
}

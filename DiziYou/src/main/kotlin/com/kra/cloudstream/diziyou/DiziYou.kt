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
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/" to "Son Eklenenler", "$mainUrl/genel/" to "Dizi Arşivi")

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
        if (path.isBlank() || path == "/" || path.startsWith("/wp-") || path in setOf("/genel/", "/hakkimizda/")) return false
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return newHomePageResponse(request.name, cards(app.get(request.data, referer = "$mainUrl/").document).take(60), false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return cards(app.get(url, referer = "$mainUrl/").document).take(50)
    }
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
            val numbering = parseEpisodeNumbers(label)
            if (numbering.second == null && !label.contains("bölüm", true) && !label.contains("bolum", true)) return@mapNotNull null
            val card = a.closest(".episode, .bolumust, article, li, [class*=episode]") ?: a
            newEpisode(href) {
                name = label.ifBlank { "Bölüm" }
                season = numbering.first ?: 1
                episode = numbering.second
                posterUrl = card.selectFirst("img")?.let { img -> listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { k -> img.attr(k).takeIf { it.isNotBlank() } }?.let { fixUrlNull(it) } }
            }
        }.distinctBy { it.data }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; this.plot = plot; this.year = year
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val frames = doc.select("iframe[src], iframe[data-src], video source[src]").mapNotNull { el ->
            listOf("src", "data-src").firstNotNullOfOrNull { k -> el.attr(k).trim().takeIf { it.isNotBlank() } }?.let { fixUrlNull(it) }
        }.distinct()
        var attempted = false
        frames.forEach { frame -> runCatching { loadExtractor(frame, data, subtitleCallback, callback); attempted = true } }
        return attempted
    }
}

/** Extracts season/episode numbering from observed labels such as "2. Sezon 3. Bölüm". */
internal fun parseEpisodeNumbers(label: String): Pair<Int?, Int?> {
    val season = Regex("(?i)(\\d+)\\.\\s*sezon").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = Regex("(?i)(\\d+)\\.\\s*(?:bölüm|bolum)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return season to episode
}

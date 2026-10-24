package com.kra.cloudstream.webteizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Initial adapter; see docs/sites/WEBTEIZLE.md for unverified live-site assumptions. */
class WebteIzle : MainAPI() {
    override var mainUrl = "https://webteizle.info"
    override var name = "WebteIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Güncel",
        "$mainUrl/yeniler" to "Yeniler",
        "$mainUrl/tavsiye-filmler" to "Tavsiye Filmler",
        "$mainUrl/odullu-filmler" to "Ödüllü Filmler",
        "$mainUrl/imdb-8" to "IMDb 8+",
        "$mainUrl/2026-filmleri" to "2026 Filmleri",
        "$mainUrl/2025-filmleri" to "2025 Filmleri"
    )

    private fun Element.attrFirst(vararg names: String): String? =
        names.firstNotNullOfOrNull { attr(it).takeIf(String::isNotBlank) }

    private fun Element.toMovie(): SearchResponse? {
        val a = selectFirst("a[href*=/hakkinda/], a[href*=/film/], a[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (!href.contains("/hakkinda/") && !href.contains("/film/")) return null
        val title = selectFirst(".filmname, .title, [itemprop=name]")?.text()?.trim()
            ?.takeIf(String::isNotBlank) ?: a.attr("title").takeIf(String::isNotBlank)
            ?: a.text().trim().takeIf(String::isNotBlank) ?: return null
        val image = (selectFirst("img")?.attrFirst("data-src", "data-lazy-src", "data-original", "src")
            ?: a.selectFirst("img")?.attrFirst("data-src", "data-lazy-src", "data-original", "src"))
        val score = selectFirst(".imdb, [itemprop=ratingValue]")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = image?.let { fixUrlNull(it) }
            this.score = Score.from10(score)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) request.data else "$base/page/$page/"
        val doc = app.get(url).document
        return newHomePageResponse(request.name,
            doc.select("div.golgever, .film-kart, .film-card, article, .movie-item")
                .mapNotNull { it.toMovie() }.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filtre?a=$encoded", referer = "$mainUrl/").document
        return doc.select("div.golgever, .film-kart, .film-card, article, .movie-item")
            .mapNotNull { it.toMovie() }.distinctBy { it.url }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?.takeIf(String::isNotBlank) ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val image = doc.selectFirst("div.card img")?.attrFirst("data-src", "data-lazy-src", "src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val yearText = doc.select("td").firstOrNull { it.text().contains("Vizyon", true) }?.nextElementSibling()?.text().orEmpty()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText)?.value?.toIntOrNull()
        val plot = doc.selectFirst("blockquote, [itemprop=description]")?.text()?.trim()
        val tags = doc.select("a[itemgroup=genre]").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val rating = doc.selectFirst("div.detail, [itemprop=ratingValue]")?.text()?.trim()?.replace(",", ".")
        val durationText = doc.select("td").firstOrNull { it.text().contains("Süre", true) }?.nextElementSibling()?.text().orEmpty()
        val duration = Regex("""\d+""").find(durationText)?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = image?.let { fixUrlNull(it) }; this.year = year; this.plot = plot
            this.tags = tags; score = Score.from10(rating); this.duration = duration
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(data).document
        val filmId = page.selectFirst("button#wip")?.attr("data-id") ?: return false
        val languages = buildList {
            if (page.selectFirst("div.golge a[href*=dublaj]") != null) add("0" to "Dublaj")
            if (page.selectFirst("div.golge a[href*=altyazi]") != null) add("1" to "Altyazı")
        }
        var emitted = false
        for ((langId, langName) in languages) {
            val alternatives = runCatching {
                app.post("$mainUrl/ajax/dataAlternatif3.asp",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                    data = mapOf("filmid" to filmId, "dil" to langId, "s" to "", "b" to "", "bot" to "0")
                ).parsedSafe<AlternativesResponse>()
            }.getOrNull()?.data.orEmpty()
            for (alternative in alternatives) {
                val embed = runCatching {
                    app.post("$mainUrl/ajax/dataEmbed.asp",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                        data = mapOf("id" to alternative.id.toString())).document
                }.getOrNull() ?: continue
                val iframe = fixUrlNull(embed.selectFirst("iframe")?.attr("src")) ?: continue
                val host = runCatching { java.net.URI(iframe).host }.getOrNull() ?: "unknown-player"
                loadExtractor(iframe, data, subtitleCallback) { link ->
                    callback(ExtractorLink(
                        source = "WebteIzle $langName • $host",
                        name = "WebteIzle $langName • $host • " + (alternative.kalitesi?.toString() ?: "?") + "p",
                        url = link.url, referer = link.referer, quality = link.quality,
                        headers = link.headers, extractorData = link.extractorData, type = link.type
                    ))
                    emitted = true
                }
            }
        }
        return emitted
    }
}
private data class AlternativesResponse(val status: String? = null, val data: List<WebteIzleAlternative> = emptyList())
private data class WebteIzleAlternative(val id: Int, val baslik: String? = null, val kalitesi: Int? = null)

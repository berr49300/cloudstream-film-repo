package com.kra.cloudstream.hatydizipal

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

data class DiziPalCatalogEntry(val title: String, val url: String, val isSeries: Boolean, val posterUrl: String?, val year: Int?)
data class DiziPalEpisodeEntry(val title: String, val url: String, val season: Int, val episode: Int)

object DiziPalHtmlParser {
    private val acceptedHosts = setOf("dizipalorjinal14.com", "www.dizipalorjinal14.com", "dizipal1430.com", "www.dizipal1430.com")

    fun isSiteUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && uri.host.orEmpty().lowercase() in acceptedHosts
    }.getOrDefault(false)

    fun parseCatalogElement(element: Element, baseUrl: String): DiziPalCatalogEntry? {
        val raw = element.attr("href").trim()
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) return null
        val href = resolve(baseUrl, raw)
        if (!isSiteUrl(href)) return null
        val path = URI(href).path.orEmpty()
        val series = path.contains("/dizi/")
        val movie = path.contains("/film/")
        if (!series && !movie) return null
        val image = element.selectFirst("img")
        val title = element.attr("title").ifBlank {
            element.selectFirst(".title, h2, h3, h4, .name")?.text()?.trim()
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: element.text().trim()
        }.trim()
        if (title.isBlank()) return null
        val imageUrl = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
        val poster = imageUrl.takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) }
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val cleanTitle = title.replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "").trim()
        return DiziPalCatalogEntry(cleanTitle, href, series, poster, year)
    }

    fun parseEpisodeEntries(doc: Document, baseUrl: String): List<DiziPalEpisodeEntry> =
        doc.select("a[href*='/bolum/']").mapNotNull { element ->
            val raw = element.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("javascript:", true)) return@mapNotNull null
            val href = resolve(baseUrl, raw)
            if (!isSiteUrl(href) || !URI(href).path.orEmpty().contains("/bolum/")) return@mapNotNull null
            val label = element.text().trim()
            val season = Regex("""(\d+)\s*[-.]?\s*sezon""", RegexOption.IGNORE_CASE)
                .find("$label $href")?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("""(\d+)\s*[-.]?\s*bolum""", RegexOption.IGNORE_CASE)
                .find("$label $href")?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            DiziPalEpisodeEntry(label.ifBlank { "$season. Sezon $episode. Bölüm" }, href, season, episode)
        }.distinctBy { it.url }

    private fun resolve(base: String, candidate: String): String =
        runCatching { URI(base).resolve(candidate).toString() }.getOrElse { candidate }
}
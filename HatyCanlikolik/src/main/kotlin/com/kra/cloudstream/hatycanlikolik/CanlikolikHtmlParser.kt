package com.kra.cloudstream.hatycanlikolik

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

data class CanlikolikPageEntry(val title: String, val url: String, val posterUrl: String?)

object CanlikolikHtmlParser {
    fun parseCatalog(doc: Document): List<CanlikolikPageEntry> {
        val items = linkedMapOf<String, CanlikolikPageEntry>()
        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) return@forEach
            val href = resolve(doc.baseUri(), raw)
            if (!isCanlikolikUrl(href) || URI(href).path.orEmpty().trim('/').isBlank()) return@forEach
            val title = titleOf(a)
            if (title.length < 2 || !looksRelevant(href, title, a)) return@forEach
            val img = a.selectFirst("img")
            val image = img?.attr("data-src").orEmpty().ifBlank { img?.attr("data-lazy-src").orEmpty() }.ifBlank { img?.attr("src").orEmpty() }
            items.putIfAbsent(href, CanlikolikPageEntry(title, href, image.takeIf { it.isNotBlank() }?.let { resolve(doc.baseUri(), it) }))
        }
        return items.values.toList()
    }

    fun isCanlikolikUrl(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase().removePrefix("www.")
        Regex("""^canlikolik\d*\.(my|live|cam)$""").matches(host)
    }.getOrDefault(false)

    private fun titleOf(a: Element): String =
        (a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: a.attr("aria-label").takeIf { it.isNotBlank() }
            ?: a.text()).trim()

    private fun looksRelevant(url: String, title: String, a: Element): Boolean {
        val source = (URI(url).path.orEmpty() + " " + title + " " + a.className() + " " + a.parent()?.attr("class").orEmpty()).lowercase()
        return listOf("mac", "match", "event", "kanal", "canli", "live", "stream", "futbol", "basketbol",
            "voleybol", "tenis", "league", "spor", "bein", "tabii", "trt", "s-sport", "ssport",
            "tv8", "atv", "a-spor", "nba", "vs", "yayın").any(source::contains)
    }

    private fun resolve(base: String, value: String): String =
        runCatching { URI(base).resolve(value).toString() }.getOrElse { value }
}
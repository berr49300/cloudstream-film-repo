package com.kra.cloudstream.dizilla

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Dizilla catalogue provider.
 *
 * It parses publicly rendered HTML and publicly exposed iframe/media elements only.
 * It does not decrypt hidden page payloads, bypass access controls, or extract protected streams.
 */
class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenen Bölümler",
        "${mainUrl}/arsiv" to "Dizi Arşivi",
        "${mainUrl}/yabanci-dizi-izle" to "Yabancı Diziler",
        "${mainUrl}/anime-izle" to "Anime",
        "${mainUrl}/kdrama-izle" to "Kore Dizileri",
        "${mainUrl}/imdb-top-100" to "IMDb Top 100"
    )

    private fun isDizillaHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "dizilla.now" || host == "www.dizilla.now"
    }

    private fun safePageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val fixed = fixUrlNull(raw.trim()) ?: return null
        val uri = runCatching { URI(fixed) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("https", "http")) return null
        if (!isDizillaHost(fixed)) return null
        return fixed
    }

    private fun posterFor(element: Element): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { key -> image.attr(key).takeIf { it.isNotBlank() } }
            ?: return null
        return fixUrlNull(raw)
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        // Support both series cards and episode links returned by site search.
        val cards = doc.select(
            "div.serie-card, div.episode-card, .series-card, .show-card, article, " +
                "a[href*='/dizi/'], a[href*='-sezon-'], a[href*='-season-'], " +
                "a[href*='-bolum'], a[href*='-bölüm'], a[href*='/episode/']"
        )

        for (card in cards) {
            val link = if (card.tagName().equals("a", ignoreCase = true)) {
                card
            } else {
                card.selectFirst("a[href]") ?: continue
            }
            val href = safePageUrl(link.attr("href")) ?: continue
            if (!seen.add(href)) continue

            val title = listOf(
                card.selectFirst(".title")?.text(),
                card.selectFirst("h2")?.text(),
                card.selectFirst("h3")?.text(),
                link.attr("title"),
                link.selectFirst("img")?.attr("alt"),
                card.selectFirst("img")?.attr("alt"),
                link.text()
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
            if (title.isBlank()) continue

            results += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = posterFor(card)
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = safePageUrl(request.data) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val target = if (page <= 1) {
            base
        } else {
            val separator = if (base.contains("?")) "&" else "?"
            "$base${separator}page=$page"
        }

        val doc = app.get(target, headers = mapOf("Referer" to "$mainUrl/")).document
        val results = parseListing(doc)
        val hasNext = doc.selectFirst(
            "a[rel=next], a.next[href], .pagination [aria-label='Next'][href]"
        ) != null
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    data class DizillaSearchEnvelope(
        @com.fasterxml.jackson.annotation.JsonProperty("data")
        val data: DizillaSearchData? = null
    )

    data class DizillaSearchData(
        @com.fasterxml.jackson.annotation.JsonProperty("state")
        val state: Boolean? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("result")
        val result: List<DizillaSearchItem>? = null
    )

    data class DizillaSearchItem(
        @com.fasterxml.jackson.annotation.JsonProperty("used_slug")
        val slug: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("object_name")
        val title: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("object_poster_url")
        val poster: String? = null
    )

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val referer = "$mainUrl/"
        // Dizilla's search is an AJAX POST. The generic /search and /?s routes
        // can return homepage/recommendation cards, which caused unrelated titles.
        val homepage = runCatching {
            app.get(referer).document
        }.getOrNull() ?: return newSearchResponseList(emptyList(), hasNext = false)

        val cKey = homepage.selectFirst("input[name=cKey]")?.attr("value").orEmpty()
        val cValue = homepage.selectFirst("input[name=cValue]")?.attr("value").orEmpty()
        if (cKey.isNotBlank() && cValue.isNotBlank()) {
            val response = runCatching {
                app.post(
                    "$mainUrl/bg/searchcontent",
                    data = mapOf("cKey" to cKey, "cValue" to cValue, "searchterm" to query.trim()),
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to referer
                    ),
                    cookies = mapOf(
                        "showAllDaFull" to "true",
                        "PHPSESSID" to app.get(referer).cookies["PHPSESSID"].orEmpty()
                    )
                ).parsedSafe<DizillaSearchEnvelope>()
            }.getOrNull()

            val results = response?.data?.result.orEmpty().mapNotNull { item ->
                val title = item.title?.trim().orEmpty()
                val slug = item.slug?.trim().orEmpty()
                if (title.isBlank() || slug.isBlank()) return@mapNotNull null
                val href = safePageUrl(if (slug.startsWith("http")) slug else "$mainUrl/${slug.removePrefix("/")}") ?: return@mapNotNull null
                val poster = fixUrlNull(item.poster.orEmpty())
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            }.distinctBy { it.url }
            if (response?.data?.state == true && results.isNotEmpty()) {
                return newSearchResponseList(results, hasNext = false)
            }
        }

        // Only use a dedicated search page as fallback; never parse homepage cards
        // as search results. Apply title relevance as a final guard.
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = runCatching {
            app.get("$mainUrl/arama?q=$encoded", headers = mapOf("Referer" to referer)).document
        }.getOrNull() ?: return newSearchResponseList(emptyList(), hasNext = false)

        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val results = parseListing(doc).filter { item ->
            val title = item.name.lowercase(Locale.ROOT)
            words.all { title.contains(it) }
        }.distinctBy { it.url }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = safePageUrl(url) ?: return null
        val doc = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/")).document

        val pageTitle = listOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property='og:title']")?.attr("content"),
            doc.selectFirst("title")?.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.replace(Regex("""\s*[-|–]\s*Dizilla\s*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+İzle\s*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val isEpisodePage = DizillaParser.parseEpisodePosition(pageUrl, pageTitle) != null
        val title = if (isEpisodePage) DizillaParser.cleanSeriesTitle(pageTitle) else pageTitle

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        )
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val yearText = listOf(
            doc.selectFirst(".year")?.text(),
            doc.selectFirst(".date")?.text(),
            doc.selectFirst("[itemprop=datePublished]")?.attr("content"),
            doc.selectFirst("meta[property='article:published_time']")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val year = Regex("""\b(?:19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val episodes = parseEpisodes(doc, pageUrl)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun parseEpisodes(doc: Document, currentUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val anchors = doc.select(
            "a[href*='-sezon-'], a[href*='-season-'], a[href*='-bolum'], " +
                "a[href*='-bölüm'], a[href*='/episode/']"
        )

        for (anchor in anchors) {
            val href = safePageUrl(anchor.attr("href")) ?: continue
            if (href == currentUrl || !seen.add(href)) continue
            val linkText = anchor.text().trim()
            val position = DizillaParser.parseEpisodePosition(href, linkText) ?: continue

            episodes += newEpisode(href) {
                name = linkText.takeIf { it.isNotBlank() }
                    ?: "${position.season}. Sezon ${position.episode}. Bölüm"
                season = position.season
                episode = position.episode
            }
        }

        val currentTitle = doc.selectFirst("h1")?.text().orEmpty()
        val currentPosition = DizillaParser.parseEpisodePosition(currentUrl, currentTitle)
        if (currentPosition != null && seen.add(currentUrl)) {
            episodes += newEpisode(currentUrl) {
                name = "${currentPosition.season}. Sezon ${currentPosition.episode}. Bölüm"
                season = currentPosition.season
                episode = currentPosition.episode
            }
        }

        return episodes
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = safePageUrl(data) ?: return false
        val doc = runCatching {
            app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/")).document
        }.getOrNull() ?: return false

        var found = inspectPublicMedia(doc, pageUrl, subtitleCallback, callback, allowOneNestedPage = true)

        // Dizilla exposes player mirrors on a separate page. Resolve the visible
        // player links and the ordinary player iframe rendered in their HTML.
        val playerPages = doc.select("a[href*='player']")
            .mapNotNull { safePageUrl(it.attr("href")) }
            .distinct()
        val pagesToInspect = if (playerPages.isEmpty()) listOf(pageUrl) else playerPages
        for (playerPage in pagesToInspect) {
            val playerDoc = if (playerPage == pageUrl) doc else runCatching {
                app.get(playerPage, headers = mapOf("Referer" to pageUrl)).document
            }.getOrNull() ?: continue

            val iframeUrl = playerDoc.selectFirst("div#playerLsDizilla iframe[src], div#playerLsDizilla iframe[data-src], iframe[src], iframe[data-src]")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?.let { fixUrlNull(it) }
                ?: continue

            val loaded = runCatching {
                loadExtractor(iframeUrl, playerPage, subtitleCallback, callback)
            }.getOrDefault(false)
            found = loaded || found
        }

        return found
    }

}

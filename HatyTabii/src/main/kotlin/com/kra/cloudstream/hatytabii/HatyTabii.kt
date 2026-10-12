package com.kra.cloudstream.hatytabii

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

/**
 * APK evidence: TabiiTvRepository and TabiiExtractor; public URL evidence:
 * tabii.com/tr/watch/live/trt1 and /_next/data/ routes.
 *
 * This adapter discovers only live pages linked by the official site's HTML.
 * It intentionally does not fabricate stream URLs; live playback is beta and
 * depends on the source/manifest being exposed to the client.
 */
class HatyTabii : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "Haty tabii Canlı"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("$mainUrl/tr/watch/live/trt1" to "Canlı Yayın")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = mapOf("Accept-Language" to "tr-TR,tr;q=0.9")).document
        val results = linkedMapOf<String, SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!isLivePage(href)) return@forEach
            val title = a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.attr("aria-label").takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().trim()
            if (title.isBlank()) return@forEach
            val poster = fixUrlNull(a.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            })
            results.putIfAbsent(href, newLiveSearchResponse(title, href) { posterUrl = poster })
        }
        // Keep the known official live route visible even when Next.js renders no anchor cards.
        if (results.isEmpty()) {
            results["$mainUrl/tr/watch/live/trt1"] =
                newLiveSearchResponse("TRT 1 Canlı", "$mainUrl/tr/watch/live/trt1")
        }
        return newHomePageResponse(request.name, results.values.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse? {
        if (!isLivePage(url)) return null
        val doc = app.get(url, headers = mapOf("Accept-Language" to "tr-TR,tr;q=0.9")).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Canlı Yayın"
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = poster
            plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        if (!isLivePage(data)) return false
        val response = app.get(data, headers = mapOf(
            "Accept-Language" to "tr-TR,tr;q=0.9",
            "Referer" to "$mainUrl/"
        ))
        val doc = response.document
        val candidates = linkedSetOf<String>()
        doc.select("iframe[src], video[src], source[src]").forEach {
            fixUrlNull(it.attr("src"))?.let(candidates::add)
        }
        // Next.js may embed public playback references in page state; only use
        // explicit URLs returned by the official page, never construct them.
        Regex("""https?:\\?/\\?/[^"\\s<>]+?\\.(?:m3u8|mpd)(?:\\?[^"\\s<>]*)?""")
            .findAll(response.text)
            .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .forEach(candidates::add)
        for (candidate in candidates) {
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                if (candidate.contains(".m3u8", true) || candidate.contains(".mpd", true)) {
                    callback.invoke(
                        com.lagradost.cloudstream3.utils.newExtractorLink(
                            source = name,
                            name = "tabii Canlı",
                            url = candidate,
                            type = if (candidate.contains(".mpd", true))
                                com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH
                            else com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                        ) { referer = data }
                    )
                } else {
                    loadExtractor(candidate, data, subtitleCallback, callback)
                }
            }
        }
        return candidates.isNotEmpty()
    }

    private fun isLivePage(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.host?.equals("www.tabii.com", true) == true &&
                uri.path.orEmpty().contains("/watch/live/", true)
        }.getOrDefault(false)
}
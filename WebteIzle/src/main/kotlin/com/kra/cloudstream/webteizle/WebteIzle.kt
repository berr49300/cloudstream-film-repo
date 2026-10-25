package com.kra.cloudstream.webteizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLEncoder

/** Initial adapter; see docs/sites/WEBTEIZLE.md for unverified live-site assumptions. */
class WebteIzle : MainAPI() {
    private val alternativesMapper by lazy { jacksonObjectMapper() }
    override var mainUrl = "https://webteizle.info"
    override var name = "WebteIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = mainPageOf(
        "$mainUrl/film-izle" to "Güncel",
        "$mainUrl/yeni-filmler" to "Yeniler",
        "$mainUrl/tavsiye-filmler" to "Tavsiye Filmler",
        "$mainUrl/odullu-filmler" to "Ödüllü Filmler",
        "$mainUrl/imdb-8" to "IMDb 8+",
        "$mainUrl/2026-filmleri" to "2026 Filmleri",
        "$mainUrl/2025-filmleri" to "2025 Filmleri"
    )

    private fun Element.attrFirst(vararg names: String): String? =
        names.firstNotNullOfOrNull { attr(it).takeIf(String::isNotBlank) }

    /**
     * Challenge detection only. This deliberately does not try to solve or bypass
     * Cloudflare/CAPTCHA; the caller fails closed so challenge HTML is not parsed
     * as a catalogue, movie detail, or video-player response.
     */
    private fun reportAccessChallenge(context: String, html: String): Boolean {
        if (!WebteIzleResolverBank.isAccessChallenge(html)) return false
        android.util.Log.w(
            "WebteIzleResolver",
            "34 SessionDependentSourceDiagnostic: BLOCKED — Cloudflare/CAPTCHA challenge detected during $context; no bypass attempted."
        )
        return true
    }

    private fun Element.toMovie(): SearchResponse? {
        val a = selectFirst("a[href*=/hakkinda/], a[href*=/film/]") ?: return null
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
        // Observed pagination links use /film-izle/2 and /yeni-filmler/2,
        // not the guessed /page/2 route.
        val url = if (page <= 1) request.data else "$base/$page"
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzleResolver",
                "34 SessionDependentSourceDiagnostic: BLOCKED/NOT TESTED — catalogue request failed (${e.javaClass.simpleName}); no bypass attempted."
            )
            return newHomePageResponse(request.name, emptyList())
        }
        if (reportAccessChallenge("getMainPage", doc.html())) {
            return newHomePageResponse(request.name, emptyList())
        }
        return newHomePageResponse(request.name,
            doc.select("div.golgever, .film-kart, .film-card, article, .movie-item")
                .mapNotNull { it.toMovie() }.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // A public reference implementation uses the site's legacy Turkish encoding.
        // Keep this hypothesis visible until live search parity is verified.
        val encoded = URLEncoder.encode(query, "ISO-8859-9")
        val doc = try {
            app.get("$mainUrl/filtre?a=$encoded", referer = "$mainUrl/").document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzleResolver",
                "34 SessionDependentSourceDiagnostic: BLOCKED/NOT TESTED — search request failed (${e.javaClass.simpleName}); no bypass attempted."
            )
            return emptyList()
        }
        if (reportAccessChallenge("search", doc.html())) return emptyList()
        return doc.select("div.golgever, .film-kart, .film-card, article, .movie-item")
            .mapNotNull { it.toMovie() }.distinctBy { it.url }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzleResolver",
                "34 SessionDependentSourceDiagnostic: BLOCKED/NOT TESTED — detail request failed (${e.javaClass.simpleName}); no bypass attempted."
            )
            return null
        }
        if (reportAccessChallenge("load", doc.html())) return null
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

    /**
     * Resolve the movie-detail page to its actual playback route before asking for sources.
     * The site's /hakkinda/<slug> page contains language/watch links, while button#wip and the
     * alternative-player AJAX chain live on /izle/altyazi/<slug> or /izle/dublaj/<slug>.
     */
    private fun normalizePlaybackPageUrl(raw: String): String? {
        val absolute = fixUrlNull(raw) ?: return null
        val uri = runCatching { java.net.URI(absolute) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val expectedHost = runCatching { java.net.URI(mainUrl).host }.getOrNull() ?: return null
        if (!host.equals(expectedHost, ignoreCase = true)) return null
        if (!uri.path.contains("/izle/altyazi/", ignoreCase = true) &&
            !uri.path.contains("/izle/dublaj/", ignoreCase = true)
        ) return null
        return absolute
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rootPage = try {
            app.get(data).document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzle",
                "Playback page request failed (\${e.javaClass.simpleName})."
            )
            return false
        }
        if (reportAccessChallenge("loadLinks", rootPage.html())) return false

        // Keep one watch page per language. Some detail pages repeat the same watch link
        // many times; those duplicates must never trigger additional source rows.
        val watchPages = linkedMapOf<String, Pair<String, String>>()
        fun addWatchPage(url: String) {
            val route = WebteIzleResolverBank.playbackLanguage(url) ?: return
            val language = route.second
            if (language !in watchPages) watchPages[language] = route.first to url
        }

        normalizePlaybackPageUrl(data)?.let(::addWatchPage)
        WebteIzleResolverBank.findPlaybackPageUrls(
            html = rootPage.html(),
            baseUrl = data,
            siteBaseUrl = mainUrl
        ).forEach(::addWatchPage)

        if (watchPages.isEmpty()) {
            android.util.Log.w(
                "WebteIzle",
                "No dubbed/subtitled watch route found; no source emitted."
            )
            return false
        }

        var emitted = false
        for ((language, route) in watchPages) {
            val (languageId, watchUrl) = route
            val link = resolveOneVidMoly(
                watchUrl = watchUrl,
                detailUrl = data,
                languageId = languageId,
                language = language,
                subtitleCallback = subtitleCallback
            )
            if (link != null) {
                callback(link)
                emitted = true
            }
        }
        return emitted
    }

    /**
     * Resolve one playable VidMoly link for one language and stop immediately.
     * Never expose intermediate player pages, unrelated hosts, or duplicate alternatives.
     */
    private suspend fun resolveOneVidMoly(
        watchUrl: String,
        detailUrl: String,
        languageId: String,
        language: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): ExtractorLink? {
        val page = try {
            app.get(watchUrl, referer = detailUrl).document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzle",
                "$language watch page failed (\${e.javaClass.simpleName})."
            )
            return null
        }
        val pageHtml = page.html()
        if (reportAccessChallenge("watch page", pageHtml)) return null

        val filmId = page.selectFirst("button#wip[data-id]")?.attr("data-id")
            ?.takeIf(String::isNotBlank)
            ?: run {
                android.util.Log.w("WebteIzle", "$language watch page has no movie ID.")
                return null
            }

        val alternativesBody = try {
            app.post(
                "$mainUrl/ajax/dataAlternatif3.asp",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to mainUrl,
                    "Referer" to watchUrl
                ),
                data = mapOf(
                    "filmid" to filmId,
                    "dil" to languageId,
                    "s" to "",
                    "b" to "",
                    "bot" to "0"
                )
            ).text
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzle",
                "$language alternatives request failed (\${e.javaClass.simpleName})."
            )
            return null
        }
        if (reportAccessChallenge("alternatives request", alternativesBody)) return null

        val alternatives = runCatching {
            alternativesMapper.readValue(alternativesBody, AlternativesResponse::class.java).data
        }.getOrElse {
            android.util.Log.w("WebteIzle", "$language alternatives response was not valid JSON.")
            return null
        }

        // Try labelled VidMoly entries first, then inspect the embed response itself. The same
        // VidMoly choice may be listed repeatedly by the site; successful resolution exits early.
        val orderedAlternatives = alternatives.sortedBy {
            if (it.baslik?.contains("vidmoly", ignoreCase = true) == true) 0 else 1
        }
        val attemptedPlayers = linkedSetOf<String>()
        for (alternative in orderedAlternatives) {
            val embedHtml = try {
                app.post(
                    "$mainUrl/ajax/dataEmbed.asp",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to mainUrl,
                        "Referer" to watchUrl
                    ),
                    data = mapOf("id" to alternative.id.toString())
                ).text
            } catch (_: Exception) {
                continue
            }
            if (WebteIzleResolverBank.isAccessChallenge(embedHtml)) {
                android.util.Log.w("WebteIzle", "$language VidMoly embed is blocked by an access challenge.")
                continue
            }

            val playerUrls = WebteIzleResolverBank.findVidMolyPlayerUrls(
                html = embedHtml,
                baseUrl = "$mainUrl/ajax/dataEmbed.asp"
            )
            for (playerUrl in playerUrls) {
                if (!attemptedPlayers.add(playerUrl)) continue

                val extractedLinks = mutableListOf<ExtractorLink>()
                val extractedSubtitles = linkedSetOf<String>()
                val pendingSubtitles = mutableListOf<SubtitleFile>()
                try {
                    loadExtractor(playerUrl, watchUrl, { subtitle ->
                        if (extractedSubtitles.add(subtitle.url)) pendingSubtitles += subtitle
                    }) { link ->
                        if (!WebteIzleResolverBank.isYouTubeUrl(link.url)) extractedLinks += link
                    }
                } catch (e: Exception) {
                    android.util.Log.d(
                        "WebteIzle",
                        "$language VidMoly extractor failed (\${e.javaClass.simpleName}); trying the direct HLS fallback."
                    )
                }

                // A host can return several quality variants. Publish only one row and prefer
                // the highest advertised quality from this single VidMoly player.
                val bestLink = extractedLinks.maxByOrNull { it.quality }
                if (bestLink != null) {
                    pendingSubtitles.forEach(subtitleCallback)
                    return newExtractorLink(
                        source = "VidMoly",
                        name = "VidMoly • $language",
                        url = bestLink.url,
                        type = bestLink.type
                    ) {
                        referer = bestLink.referer
                        quality = bestLink.quality
                        headers = bestLink.headers
                        extractorData = bestLink.extractorData
                    }
                }

                // Some VidMoly pages expose a JWPlayer file field before the native extractor
                // can return a link. Read the page once; challenge responses are never bypassed.
                val playerHtml = try {
                    app.get(
                        playerUrl,
                        headers = mapOf("Sec-Fetch-Dest" to "iframe"),
                        referer = "$mainUrl/"
                    ).text
                } catch (_: Exception) {
                    null
                }
                if (playerHtml.isNullOrBlank()) continue
                if (WebteIzleResolverBank.isAccessChallenge(playerHtml)) {
                    android.util.Log.w("WebteIzle", "$language VidMoly page is blocked by Cloudflare/CAPTCHA.")
                    continue
                }
                val master = WebteIzleResolverBank.extractVidMolyMasterPlaylist(playerHtml) ?: continue
                return newExtractorLink(
                    source = "VidMoly",
                    name = "VidMoly • $language",
                    url = master,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = runCatching {
                        val uri = java.net.URI(playerUrl)
                        "\${uri.scheme}://\${uri.authority}/"
                    }.getOrDefault("$mainUrl/")
                    quality = getQualityFromName(master)
                }
            }
        }
        android.util.Log.w("WebteIzle", "No playable VidMoly link found for $language.")
        return null
    }
}

private data class AlternativesResponse(
    val status: String? = null,
    val data: List<WebteIzleAlternative> = emptyList()
)

private data class WebteIzleAlternative(
    val id: Int,
    val baslik: String? = null,
    val kalitesi: Int? = null
)

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

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val page = try {
            app.get(data).document
        } catch (e: Exception) {
            android.util.Log.w(
                "WebteIzleResolver",
                "34 SessionDependentSourceDiagnostic: BLOCKED/NOT TESTED — detail request failed (${e.javaClass.simpleName}); access controls are not bypassed."
            )
            return false
        }
        val pageHtml = page.html()
        val accessChallenge = reportAccessChallenge("loadLinks", pageHtml)
        val filmId = page.selectFirst("button#wip")?.attr("data-id")?.takeIf { it.isNotBlank() }
        val languages = buildList {
            if (page.selectFirst("div.golge a[href*=dublaj]") != null) add("0" to "Dublaj")
            if (page.selectFirst("div.golge a[href*=altyazi]") != null) add("1" to "Altyazı")
        }
        var emitted = false
        val fetchedIframePages = linkedSetOf<String>()
        val fetchedManifestUrls = linkedSetOf<String>()
        val headCheckedMediaUrls = linkedSetOf<String>()
        // Multiple AJAX language tabs or overlapping scanner passes may contain the exact same
        // URL. Keep one entry per resolver ID / candidate URL / kind so IDs remain testable
        // without repeating one source several times under the same method.
        val seenCandidateKeys = linkedSetOf<String>()

        fun candidateReferer(candidate: WebteIzleResolverCandidate): String =
            candidate.sourcePageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: data

        val maxNestedPlayerPages = 8
        val maxManifests = 8
        val maxMediaHeadChecks = 8

        suspend fun emitAttempts(
            attempts: List<WebteIzleResolverAttempt>,
            langName: String,
            fallbackQuality: Int? = null,
            probeNestedPages: Boolean = true,
            probeManifests: Boolean = true
        ) {
            attempts.forEach { attempt ->
                // IDs/statuses are logged without URLs, tokens, or player query strings.
                android.util.Log.d(
                    "WebteIzleResolver",
                    "${attempt.resolverId.toString().padStart(2, '0')} ${attempt.resolverName}: ${attempt.status} — ${attempt.reason}"
                )
                for (candidate in attempt.candidates) {
                    val id = attempt.resolverId.toString().padStart(2, '0')
                    val candidateKey = "$id|${candidate.kind}|${candidate.url}|${candidate.language.orEmpty()}"
                    if (!seenCandidateKeys.add(candidateKey)) {
                        android.util.Log.d(
                            "WebteIzleResolver",
                            "$id ${attempt.resolverName}: DUPLICATE_SUPPRESSED — same candidate URL already processed for this resolver."
                        )
                        continue
                    }
                    val baseLabel = "$id • ${attempt.resolverName} • $langName"
                    when (candidate.kind) {
                        WebteIzleCandidateKind.PLAYER -> {
                            val host = runCatching { java.net.URI(candidate.url).host }.getOrNull() ?: "unknown-player"
                            val routeLabel = if (attempt.resolverId == 17) baseLabel
                                else "$baseLabel → 17 • ExtractorRegistryResolver"
                            val family = WebteIzleResolverBank.playerFamily(candidate.url)
                            val playerLabel = family?.let { "$it ($host)" } ?: host
                            val qualityLabel = (fallbackQuality?.takeIf { it > 0 }?.toString() ?: "?") + "p"
                            // loadExtractor's callback is non-suspending, while this provider's
                            // callback is suspend. Collect extractor results first, then emit them
                            // from the surrounding suspend function.
                            val extractedLinks = mutableListOf<ExtractorLink>()
                            try {
                                loadExtractor(candidate.url, data, subtitleCallback) { link ->
                                    // This callback is non-suspending: only collect the already-built
                                    // extractor link here. Provider callbacks run after extraction returns.
                                    extractedLinks += link
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(
                                    "WebteIzleResolver",
                                    "$id ${attempt.resolverName}: EXTRACTOR_ERROR — ${e.javaClass.simpleName}; URL/query omitted."
                                )
                            }
                            if (extractedLinks.isEmpty()) {
                                android.util.Log.d(
                                    "WebteIzleResolver",
                                    "$id ${attempt.resolverName}: NO_MEDIA_LINK — extractor did not return a media link for $playerLabel."
                                )
                                // VidMoly exposes a plain JWPlayer file field in many embeds.
                                // If the native extractor returned no link, try a separate non-JS
                                // fallback. If the normal request gets a challenge, report BLOCKED.
                                if (family == "VidMoly") {
                                    val playerHtml = try {
                                        app.get(
                                            candidate.url,
                                            headers = mapOf("Sec-Fetch-Dest" to "iframe"),
                                            referer = "$mainUrl/"
                                        ).text
                                    } catch (_: Exception) {
                                        null
                                    }
                                    when {
                                        playerHtml.isNullOrBlank() -> android.util.Log.d(
                                            "WebteIzleResolver",
                                            "41 VidMolyMasterPlaylistResolver: NOT TESTED — embed response unavailable."
                                        )
                                        WebteIzleResolverBank.isAccessChallenge(playerHtml) -> android.util.Log.w(
                                            "WebteIzleResolver",
                                            "41 VidMolyMasterPlaylistResolver: BLOCKED — VidMoly returned a Cloudflare/CAPTCHA challenge; no bypass attempted."
                                        )
                                        else -> {
                                            val master = WebteIzleResolverBank.extractVidMolyMasterPlaylist(playerHtml)
                                            if (master == null) {
                                                android.util.Log.d(
                                                    "WebteIzleResolver",
                                                    "41 VidMolyMasterPlaylistResolver: NOT FOUND — no absolute .m3u8 file field in the embed response."
                                                )
                                            } else {
                                                val playerReferer = runCatching {
                                                    val uri = java.net.URI(candidate.url)
                                                    "${uri.scheme}://${uri.authority}/"
                                                }.getOrDefault("$mainUrl/")
                                                callback(newExtractorLink(
                                                    source = "41 • VidMolyMasterPlaylistResolver",
                                                    name = "41 • VidMolyMasterPlaylistResolver • $langName • VidMoly • HLS • Aday • $qualityLabel",
                                                    url = master,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    referer = playerReferer
                                                    quality = fallbackQuality?.takeIf { it > 0 } ?: getQualityFromName(master)
                                                })
                                                emitted = true
                                            }
                                        }
                                    }
                                }
                            }
                            for (link in extractedLinks) {
                                if (WebteIzleResolverBank.isYouTubeUrl(link.url)) {
                                    android.util.Log.w(
                                        "WebteIzleResolver",
                                        "$id ${attempt.resolverName}: YOUTUBE_MEDIA_SUPPRESSED — extractor returned a YouTube/Google video URL; not published as a film source."
                                    )
                                    continue
                                }
                                callback(newExtractorLink(
                                    source = routeLabel + " • " + playerLabel,
                                    name = routeLabel + " • " + playerLabel + " • Aday • " + qualityLabel,
                                    url = link.url,
                                    type = link.type
                                ) {
                                    referer = link.referer
                                    quality = link.quality
                                    headers = link.headers
                                    extractorData = link.extractorData
                                })
                                emitted = true
                            }

                            // Follow a bounded, one-hop iframe chain only from directly discovered
                            // iframe candidates. The host is not logged with URL query parameters.
                            if (probeNestedPages && attempt.resolverId == 15 &&
                                candidate.url !in fetchedIframePages &&
                                fetchedIframePages.size < maxNestedPlayerPages
                            ) {
                                fetchedIframePages += candidate.url
                                val childHtml = try {
                                    app.get(candidate.url, referer = candidateReferer(candidate)).text
                                } catch (_: Exception) {
                                    null
                                }
                                if (childHtml.isNullOrBlank()) {
                                    android.util.Log.d(
                                        "WebteIzleResolver",
                                        "16 NestedIframeResolver: NOT TESTED — child player response unavailable"
                                    )
                                } else if (WebteIzleResolverBank.isAccessChallenge(childHtml)) {
                                    android.util.Log.w(
                                        "WebteIzleResolver",
                                        "34 SessionDependentSourceDiagnostic: BLOCKED — child player returned Cloudflare/CAPTCHA challenge HTML; no bypass attempted."
                                    )
                                } else {
                                    val nestedUrls = WebteIzleResolverBank.findIframeUrls(childHtml, candidate.url)
                                        .filterNot { it == candidate.url }
                                    val nestedAttempts = WebteIzleResolverBank.inspect(
                                        html = childHtml,
                                        baseUrl = candidate.url,
                                        nestedIframeUrls = nestedUrls,
                                        refererUrl = candidate.url
                                    )
                                    emitAttempts(
                                        attempts = nestedAttempts,
                                        langName = "$langName iframe",
                                        fallbackQuality = fallbackQuality,
                                        probeNestedPages = false,
                                        probeManifests = false
                                    )
                                }
                            }
                        }
                        WebteIzleCandidateKind.MEDIA -> {
                            val path = candidate.url.substringBefore('?').substringBefore('#')
                            val extension = path.substringAfterLast('.', "").lowercase()
                            val quality = fallbackQuality ?: getQualityFromName(candidate.url)
                            val mediaType = when (extension) {
                                "m3u8" -> ExtractorLinkType.M3U8
                                "mpd" -> ExtractorLinkType.DASH
                                else -> null
                            }
                            callback(newExtractorLink(
                                source = baseLabel,
                                name = "$baseLabel • ${extension.ifBlank { "media" }.uppercase()} • Aday • ${quality}p",
                                url = candidate.url,
                                type = mediaType
                            ) {
                                referer = candidateReferer(candidate)
                                this.quality = quality
                            })
                            emitted = true

                            // Read actual playlist/manifest bodies when a discovered candidate is
                            // a manifest. Do not download direct media files merely to inspect them.
                            if (probeManifests && extension in setOf("m3u8", "mpd") &&
                                candidate.url !in fetchedManifestUrls &&
                                fetchedManifestUrls.size < maxManifests
                            ) {
                                fetchedManifestUrls += candidate.url
                                val manifest = try {
                                    app.get(candidate.url, referer = data).text
                                } catch (_: Exception) {
                                    null
                                }
                                val validManifest = when (extension) {
                                    "m3u8" -> manifest?.contains("#EXTM3U") == true
                                    "mpd" -> manifest?.contains("<MPD", ignoreCase = true) == true
                                    else -> false
                                }
                                android.util.Log.d(
                                    "WebteIzleResolver",
                                    "${if (extension == "m3u8") "23 HlsMasterPlaylistResolver" else "25 DashManifestResolver"}: " +
                                        if (validManifest) "RESPONSE_VALID — expected manifest marker found"
                                        else "NOT TESTED/INVALID — manifest body unavailable or expected marker absent"
                                )
                                if (!manifest.isNullOrBlank() && validManifest) {
                                    val manifestAttempts = WebteIzleResolverBank.inspect(
                                        html = manifest,
                                        baseUrl = candidate.url,
                                        refererUrl = candidateReferer(candidate)
                                    )
                                    emitAttempts(
                                        attempts = manifestAttempts,
                                        langName = "$langName $extension",
                                        fallbackQuality = fallbackQuality,
                                        probeNestedPages = false,
                                        probeManifests = false
                                    )
                                }
                            } else if (probeManifests && extension in setOf("mp4", "m4v", "webm", "mkv", "avi", "mov") &&
                                candidate.url !in headCheckedMediaUrls &&
                                headCheckedMediaUrls.size < maxMediaHeadChecks
                            ) {
                                headCheckedMediaUrls += candidate.url
                                val contentType = try {
                                    val headers = app.head(candidate.url, referer = candidateReferer(candidate)).headers
                                    headers["Content-Type"] ?: headers["content-type"]
                                } catch (_: Exception) {
                                    null
                                }
                                val responseStatus = if (contentType.isNullOrBlank()) {
                                    "NOT TESTED — HEAD failed or Content-Type unavailable"
                                } else {
                                    "RESPONSE_VALID? — HEAD returned Content-Type=$contentType; validate byte ranges and actual playback separately"
                                }
                                android.util.Log.d(
                                    "WebteIzleResolver",
                                    "26/27 DirectMediaFileResolver: $responseStatus"
                                )
                            }
                        }
                        WebteIzleCandidateKind.SUBTITLE -> {
                            subtitleCallback(SubtitleFile(candidate.language ?: "Altyazı", candidate.url))
                        }
                    }
                }
            }
        }

        // Inspect the detail document too; a direct source must not depend on AJAX succeeding.
        emitAttempts(WebteIzleResolverBank.inspect(pageHtml, data), "Sayfa")

        if (filmId == null) {
            android.util.Log.d(
                "WebteIzleResolver",
                if (accessChallenge) {
                    "34 SessionDependentSourceDiagnostic: BLOCKED — Cloudflare/CAPTCHA response has no movie ID; direct-page scan only, no bypass attempted."
                } else {
                    "19 ObservedAjaxResolver: NOT TESTED — button#wip/data-id was absent; direct-page candidates were still scanned."
                }
            )
            return emitted
        }
        if (languages.isEmpty()) {
            android.util.Log.d(
                "WebteIzleResolver",
                "19 ObservedAjaxResolver: NOT TESTED — no recognised language/playback link was present on the detail page"
            )
            return emitted
        }

        for ((langId, langName) in languages) {
            val alternatives = try {
                val response = app.post(
                    "$mainUrl/ajax/dataAlternatif3.asp",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                    data = mapOf("filmid" to filmId, "dil" to langId, "s" to "", "b" to "", "bot" to "0")
                )
                // Read the body once so a challenge HTML page can be identified before JSON parsing.
                val body = response.text
                if (reportAccessChallenge("dataAlternatif3.asp", body)) {
                    emptyList()
                } else {
                    val parsed = AppUtils.tryParseJson<AlternativesResponse>(body)
                    if (parsed == null) {
                        android.util.Log.w(
                            "WebteIzleResolver",
                            "19 ObservedAjaxResolver: NOT TESTED — alternatives response was not parseable; current payload shape/status is unknown."
                        )
                        emptyList()
                    } else {
                        if (parsed.data.isEmpty()) {
                            android.util.Log.d(
                                "WebteIzleResolver",
                                "19 ObservedAjaxResolver: NOT TESTED — parsed response contained no alternatives; an empty list does not prove the film has no source."
                            )
                        }
                        parsed.data
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "WebteIzleResolver",
                    "19 ObservedAjaxResolver: BLOCKED/NOT TESTED — alternatives request failed (${e.javaClass.simpleName}); no bypass attempted."
                )
                emptyList()
            }
            for (alternative in alternatives) {
                val embed = try {
                    app.post(
                        "$mainUrl/ajax/dataEmbed.asp",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                        data = mapOf("id" to alternative.id.toString())
                    ).document
                } catch (e: Exception) {
                    android.util.Log.w(
                        "WebteIzleResolver",
                        "19 ObservedAjaxResolver: BLOCKED/NOT TESTED — dataEmbed.asp request failed (${e.javaClass.simpleName}); URL omitted."
                    )
                    continue
                }
                val embedHtml = embed.html()
                if (reportAccessChallenge("dataEmbed.asp", embedHtml)) continue
                val attempts = WebteIzleResolverBank.inspect(
                    html = embedHtml,
                    // AJAX embed fragments use the site's origin as their relative-URL base;
                    // using the film detail URL here can mis-resolve relative iframe paths.
                    baseUrl = "$mainUrl/ajax/dataEmbed.asp",
                    observedAjaxResponse = true,
                    hintedPlayerName = alternative.baslik,
                    refererUrl = data
                )
                emitAttempts(attempts, langName, alternative.kalitesi)
            }
        }
        return emitted
    }
}
private data class AlternativesResponse(val status: String? = null, val data: List<WebteIzleAlternative> = emptyList())
private data class WebteIzleAlternative(val id: Int, val baslik: String? = null, val kalitesi: Int? = null)

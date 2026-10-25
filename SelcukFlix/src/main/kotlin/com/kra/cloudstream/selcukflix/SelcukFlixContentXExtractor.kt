package com.kra.cloudstream.selcukflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ContentX / Pichive / PlayRu / DPlayer ailesi oynatıcılar için alan adından bağımsız çözücü.
 * Örnek: https://<host>/iframe.php?v=<hash>
 */
open class SelcukFlixContentXExtractor : ExtractorApi() {
    override val name            = "SelcukFlix ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    companion object {
        fun canHandle(url: String): Boolean = SelcukFlixContentXSourceParser.canHandle(url)
        fun uygunMu(url: String): Boolean = canHandle(url)
    }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tamUrl  = if (url.startsWith("//")) "https:$url" else url
        val base    = Regex("""^(https?://[^/]+)""").find(tamUrl)?.groupValues?.get(1) ?: return
        val host    = base.substringAfter("://").substringAfter("four.").substringAfter("sn.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        // Keep caller-supplied referer variants separate: ID 15 can test the episode page,
        // while ID 33 can test the site-origin context.
        val extRef  = referer ?: "https://selcukflix.app/"
        val iframeHeaders = browserHeaders + ("Referer" to extRef)
        val iSource = try {
            app.get(tamUrl, headers = iframeHeaders, referer = extRef).text
        } catch (_: Exception) {
            Log.w("SelcukFlixContentX", "Player iframe request failed; URL omitted.")
            return
        }
        if (SelcukFlixResolverBank.isAccessChallenge(iSource)) {
            Log.w("SelcukFlixContentX", "Player iframe returned an access challenge; no bypass attempted.")
            return
        }

        val subtitleTracks = SelcukFlixContentXSubtitleParser.parse(iSource, tamUrl)
        subtitleTracks.forEach { track ->
            val subtitleHost = runCatching { java.net.URI(track.url).host }.getOrNull().orEmpty()
            Log.d("SelcukFlixSubtitle", "Altyazı adayı ayrıştırıldı: ${track.label} (${subtitleHost})")
            subtitleCallback.invoke(
                newSubtitleFile(track.label, track.url) {
                    this.headers = browserHeaders + mapOf("Referer" to tamUrl)
                }
            )
        }
        if (subtitleTracks.isEmpty()) {
            Log.d("SelcukFlixSubtitle", "Player sayfasında tanınan bir harici altyazı izi bulunamadı.")
        }

        val tokens = SelcukFlixContentXSourceParser.playerTokens(iSource)
        if (tokens.isEmpty()) {
            val directFiles = SelcukFlixContentXSourceParser.mediaFiles(iSource, tamUrl)
            directFiles.flatMap { file ->
                SelcukFlixContentXSourceParser.mediaFileCandidates(file, tamUrl, 7, 39)
            }.distinctBy { it.resolverId to it.url }.forEach { candidate ->
                callback.invoke(newExtractorLink(
                    host,
                    "${candidate.resolverId.toString().padStart(2, '0')} • ${SelcukFlixResolverBank.resolverName(candidate.resolverId)} • ${candidate.label}",
                    candidate.url,
                    linkType(candidate.url) ?: ExtractorLinkType.M3U8
                ) {
                    this.referer = tamUrl
                    this.headers = browserHeaders + mapOf("Referer" to tamUrl)
                    this.quality = Qualities.Unknown.value
                })
            }
            if (directFiles.isEmpty()) {
                android.util.Log.w("SelcukFlixContentX", "No openPlayer token or direct file was found in the ordinary player response.")
            }
            return
        }

        tokens.forEach { token ->
            kaynakEkle(
                base, token.token, tamUrl, "${host} • ${token.label}",
                iframeHeaders, extRef, callback
            )
        }
    }

    private fun linkType(url: String): ExtractorLinkType? = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        Regex("""(?i)\.(mp4|m4v|webm|mkv|avi|mov)(?:$|[?#])""").containsMatchIn(url) -> ExtractorLinkType.VIDEO
        else -> null
    }

    private suspend fun kaynakEkle(
        base: String,
        token: String,
        iframe: String,
        name: String,
        iframeHeaders: Map<String, String>,
        contextReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "$base/source2.php?v=" + java.net.URLEncoder.encode(token, "UTF-8")
        val apiHeaders = iframeHeaders + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to iframe
        )
        val vidSource = try {
            app.get(apiUrl, headers = apiHeaders, referer = iframe).text
        } catch (_: Exception) {
            android.util.Log.w("SelcukFlixContentX", "$name source API request failed.")
            return
        }
        if (SelcukFlixResolverBank.isAccessChallenge(vidSource)) {
            android.util.Log.w("SelcukFlixContentX", "$name source API is blocked by an access challenge.")
            return
        }
        val media = SelcukFlixContentXSourceParser.mediaFiles(vidSource, apiUrl)
        if (media.isEmpty()) {
            android.util.Log.w("SelcukFlixContentX", "$name source API returned no recognized media file.")
        }
        media.flatMap { file ->
            SelcukFlixContentXSourceParser.mediaFileCandidates(file, apiUrl)
        }.distinctBy { it.resolverId to it.url }.forEach { candidate ->
            val mediaOrigin = runCatching {
                java.net.URI(candidate.url).let { "${it.scheme}://${it.host}/" }
            }.getOrNull()
            val resolverName = SelcukFlixResolverBank.resolverName(candidate.resolverId)
            val baseLabel = "${candidate.resolverId.toString().padStart(2, '0')} • $resolverName • $name • ${candidate.label}"

            // The media URL is observed, but its required Referer/User-Agent varies by host.
            // Offer combinations for the same URL instead of silently deduplicating them by URL.
            val refererContexts = listOf(
                "player" to iframe,
                "site" to "https://selcukflix.app/",
                "page" to contextReferer,
                "media-origin" to (mediaOrigin ?: iframe)
            ).distinctBy { it.second }

            refererContexts.forEach { (contextLabel, contextUrl) ->
                listOf(true, false).forEach { browserUserAgent ->
                    val variantLabel = "$baseLabel • Referer=$contextLabel • " +
                        if (browserUserAgent) "Chrome-UA" else "CloudStream-UA"
                    val requestHeaders = if (browserUserAgent) {
                        browserHeaders + mapOf("Referer" to contextUrl)
                    } else {
                        mapOf("Referer" to contextUrl)
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = variantLabel,
                            name = variantLabel,
                            url = candidate.url,
                            type = linkType(candidate.url) ?: ExtractorLinkType.M3U8
                        ) {
                            this.referer = contextUrl
                            this.headers = requestHeaders
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            val mediaHost = runCatching { java.net.URI(candidate.url).host }.getOrNull().orEmpty()
            Log.d("SelcukFlixContentX", "$baseLabel candidates emitted for $mediaHost across request-context variants.")
        }
    }
}

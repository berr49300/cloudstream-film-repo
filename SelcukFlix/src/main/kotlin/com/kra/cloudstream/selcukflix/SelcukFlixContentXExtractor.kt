package com.kra.cloudstream.selcukflix

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

internal data class SelcukFlixPlayerToken(val value: String, val label: String)
internal data class SelcukFlixMediaFile(val url: String, val track: String, val quality: String)

/**
 * Dedicated resolver for the Pichive/ContentX iframe flow used by SelcukFlix.
 *
 * The observed request sequence is:
 *  1. GET iframe.php?v=... with Referer: https://selcukflix.app/
 *  2. Read openPlayer(token)
 *  3. GET source2.php?v=token with the iframe URL as Referer
 *  4. Parse playlist[].sources[].file and prefer the corresponding master.m3u8 URL.
 *
 * It does not solve challenges or pretend an iframe page is a playable media link.
 */
open class SelcukFlixContentXExtractor : ExtractorApi() {
    override val name = "SelcukFlix Pichive"
    override val mainUrl = "https://four.pichive.online"
    override val requiresReferer = true

    companion object {
        fun canHandle(url: String): Boolean = SelcukFlixPichiveParser.canHandle(url)
    }

    private val mapper by lazy { jacksonObjectMapper() }
    private val siteReferer = "https://selcukflix.app/"
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframeUrl = if (url.startsWith("//")) "https:$url" else url
        val iframeHeaders = browserHeaders + ("Referer" to siteReferer)
        val iframeHtml = try {
            app.get(iframeUrl, headers = iframeHeaders, referer = siteReferer).text
        } catch (_: Exception) {
            Log.w(name, "Iframe request failed.")
            return
        }
        if (SelcukFlixResolverBank.isAccessChallenge(iframeHtml)) {
            Log.w(name, "Iframe returned an access challenge; no bypass attempted.")
            return
        }

        SelcukFlixPichiveParser.subtitles(iframeHtml, iframeUrl).forEach { track ->
            subtitleCallback(newSubtitleFile(track.first, track.second) {
                this.headers = browserHeaders + ("Referer" to iframeUrl)
            })
        }

        val tokens = SelcukFlixPichiveParser.playerTokens(iframeHtml)
        if (tokens.isEmpty()) {
            // Some versions expose the file directly in the ordinary iframe response.
            val directRoot = runCatching { mapper.readTree(iframeHtml) }.getOrNull()
            val files = directRoot?.let { SelcukFlixPichiveParser.files(it, iframeUrl) }.orEmpty()
            files.forEach { emitMedia(it, iframeUrl, callback) }
            if (files.isEmpty()) Log.w(name, "No openPlayer token or direct media field found.")
            return
        }

        val emitted = linkedSetOf<String>()
        for (token in tokens) {
            val apiUrl = SelcukFlixPichiveParser.buildSourceApiUrl(iframeUrl, token.value) ?: continue
            val response = try {
                app.get(
                    apiUrl,
                    headers = browserHeaders + mapOf(
                        "Referer" to iframeUrl,
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = iframeUrl
                ).text
            } catch (_: Exception) {
                Log.w(name, "source2.php request failed for a discovered player token.")
                continue
            }
            if (SelcukFlixResolverBank.isAccessChallenge(response)) {
                Log.w(name, "source2.php returned an access challenge; no bypass attempted.")
                continue
            }

            val payload = responsePayload(response)
            val root = runCatching { mapper.readTree(payload) }.getOrNull()
            val files = root?.let { SelcukFlixPichiveParser.files(it, apiUrl) }.orEmpty()
            files.forEach { file ->
                val label = if (file.track.isBlank() || file.track.equals("Orijinal", true)) {
                    token.label
                } else file.track
                val labelled = file.copy(track = label)
                val candidates = SelcukFlixPichiveParser.mediaCandidates(labelled, apiUrl)
                candidates.forEach { candidate ->
                    val key = candidate.url + "|" + candidate.track + "|" + candidate.quality
                    if (emitted.add(key)) {
                        emitMedia(candidate, iframeUrl, callback)
                    }
                }
            }

            SelcukFlixPichiveParser.subtitles(payload, apiUrl).forEach { track ->
                subtitleCallback(newSubtitleFile(track.first, track.second) {
                    this.headers = browserHeaders + ("Referer" to iframeUrl)
                })
            }
            if (files.isEmpty()) Log.w(name, "source2.php returned no recognized file field.")
        }
    }

    private suspend fun responsePayload(response: String): String {
        val root = runCatching { mapper.readTree(response) }.getOrNull()
        val wrapped = root?.get("response")?.takeIf { it.isTextual }?.asText()
        if (!wrapped.isNullOrBlank()) {
            SelcukFlixResolverBank.decodeSitePayload(wrapped)?.let { return it }
        }
        return SelcukFlixResolverBank.decodeSitePayload(response) ?: response
    }

    private suspend fun emitMedia(
        file: SelcukFlixMediaFile,
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = SelcukFlixResolverBank.normalizeUrl(file.url, iframeUrl) ?: return
        val path = runCatching { URI(normalized).path.orEmpty().lowercase() }.getOrDefault("")
        val type = when {
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
                path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
        val quality = when (file.quality.uppercase()) {
            "4K", "2160P" -> Qualities.P2160.value
            "1080P", "FHD" -> Qualities.P1080.value
            "720P", "HD" -> Qualities.P720.value
            "480P" -> Qualities.P480.value
            "360P" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        callback(
            newExtractorLink(
                source = name,
                name = listOf(file.track, file.quality).filter { it.isNotBlank() }.joinToString(" • ")
                    .ifBlank { "SelcukFlix kaynağı" },
                url = normalized,
                type = type
            ) {
                this.referer = iframeUrl
                this.headers = browserHeaders + ("Referer" to iframeUrl)
                this.quality = quality
            }
        )
    }
}

internal object SelcukFlixPichiveParser {
    private val mapper by lazy { jacksonObjectMapper() }

    /**
     * SelcukFlix's player hostname can rotate. The caller passes iframe URLs discovered
     * in SelcukFlix page data, so match the observed endpoint shape rather than a stale
     * allowlist of hostnames.
     */
    fun canHandle(url: String): Boolean = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty().lowercase()
        val hasPlayerToken = uri.rawQuery.orEmpty().split("&").any { parameter ->
            val rawName = parameter.substringBefore("=")
            val name = runCatching {
                java.net.URLDecoder.decode(rawName, "UTF-8")
            }.getOrDefault(rawName)
            name.equals("v", true) && parameter.substringAfter("=", "").isNotBlank()
        }
        scheme in setOf("http", "https") &&
            host.isNotBlank() &&
            uri.userInfo == null &&
            path.endsWith("/iframe.php") &&
            hasPlayerToken
    }.getOrDefault(false)

    fun playerTokens(html: String): List<SelcukFlixPlayerToken> {
        if (html.isBlank() || SelcukFlixResolverBank.isAccessChallenge(html)) return emptyList()
        val normalized = normalizeEscapes(html)
        val found = linkedMapOf<String, SelcukFlixPlayerToken>()
        Regex("""(?is)(?:window\s*\.\s*)?openPlayer\s*\(\s*['"]([^'"]+)['"]""")
            .findAll(normalized).forEach { match ->
                val token = match.groupValues[1].trim()
                if (token.isNotBlank()) found.putIfAbsent(token, SelcukFlixPlayerToken(token, "Orijinal / Altyazılı"))
            }
        Regex("""(?is),\s*['"]([^'"]{8,})['"]\s*,\s*['"]Türkçe(?:\s+Dublaj)?['"]""")
            .findAll(normalized).forEach { match ->
                val token = match.groupValues[1].trim()
                if (token.isNotBlank()) found[token] = SelcukFlixPlayerToken(token, "Türkçe Dublaj")
            }
        return found.values.toList()
    }

    fun files(root: JsonNode, baseUrl: String): List<SelcukFlixMediaFile> {
        val found = linkedMapOf<String, SelcukFlixMediaFile>()

        fun add(raw: String?, track: String?, quality: String?) {
            if (raw.isNullOrBlank()) return
            if (Regex("""(?i)\.(vtt|srt|ass|ssa|ttml)(?:$|[?#])""").containsMatchIn(raw)) return
            val url = SelcukFlixResolverBank.normalizeUrl(raw, baseUrl) ?: return
            val key = url + "|" + track.orEmpty() + "|" + quality.orEmpty()
            found.putIfAbsent(
                key,
                SelcukFlixMediaFile(
                    url = url,
                    track = track.orEmpty().ifBlank { "Orijinal" },
                    quality = quality.orEmpty().ifBlank { "HD" }
                )
            )
        }

        fun walk(node: JsonNode, inheritedTrack: String? = null) {
            when {
                node.isObject -> {
                    val track = node.get("title")?.takeIf { it.isValueNode }?.asText()
                        ?.takeIf { it.isNotBlank() } ?: inheritedTrack
                    val sourceList = node.get("sources")
                    if (sourceList?.isArray == true) {
                        sourceList.forEach { source ->
                            add(
                                source.get("file")?.asText(),
                                track,
                                source.get("label")?.asText() ?: source.get("quality")?.asText()
                            )
                            walk(source, track)
                        }
                    } else {
                        val file = node.get("file")
                        if (file?.isTextual == true) {
                            add(
                                file.asText(),
                                track,
                                node.get("label")?.asText() ?: node.get("quality")?.asText()
                            )
                        }
                        node.fields().forEachRemaining { entry ->
                            if (entry.value.isContainerNode) walk(entry.value, track)
                        }
                    }
                }
                node.isArray -> node.forEach { walk(it, inheritedTrack) }
            }
        }
        walk(root)
        return found.values.toList()
    }

    /**
     * Builds the observed source2.php request without passing an already encoded query
     * through a URI component constructor (which would turn %2F into %252F).
     */
    fun buildSourceApiUrl(iframeUrl: String, token: String): String? {
        val uri = runCatching { URI(iframeUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: return null
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
        val query = "v=" + URLEncoder.encode(token, "UTF-8")
        val candidate = "$scheme://$authority/source2.php?$query"
        return runCatching { URI(candidate).toASCIIString() }.getOrNull()
    }

    /**
     * The observed player exposes a PHP wrapper whose playable URL is master.m3u8.
     * Preserve the raw query exactly because it may contain encoded signature material.
     * Emit that real manifest first/alone rather than cluttering CloudStream with the
     * wrapper endpoint as though it were another video source.
     */
    fun mediaCandidates(rawUrl: String, baseUrl: String): List<SelcukFlixMediaFile> =
        mediaCandidates(SelcukFlixMediaFile(rawUrl, "", "HLS"), baseUrl)

    fun mediaCandidates(source: SelcukFlixMediaFile, baseUrl: String): List<SelcukFlixMediaFile> {
        val url = SelcukFlixResolverBank.normalizeUrl(source.url, baseUrl) ?: return emptyList()
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val path = uri.rawPath.orEmpty()
        val lower = path.lowercase()
        val transformedPath = when {
            lower.endsWith("/m.php") -> path.dropLast("m.php".length) + "master.m3u8"
            // Do not invent a master playlist for unrelated PHP endpoints.
            lower.endsWith(".php") -> return emptyList()
            else -> return listOf(
                SelcukFlixMediaFile(url, source.track.ifBlank { "Direct source" }, source.quality.ifBlank { "HLS" })
            )
        }

        val scheme = uri.scheme ?: return emptyList()
        val authority = uri.rawAuthority ?: return emptyList()
        val candidate = buildString {
            append(scheme).append("://").append(authority).append(transformedPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        val transformed = runCatching { URI(candidate).toASCIIString() }.getOrNull()
            ?: return emptyList()
        if (transformed == url) {
            return listOf(
                SelcukFlixMediaFile(url, source.track.ifBlank { "Direct source" }, source.quality.ifBlank { "HLS" })
            )
        }
        // Prefer the known derived playlist but retain raw m.php as a second attempt.
        // The mapping is still a hypothesis until live playback is verified.
        val quality = source.quality.ifBlank { "HLS" }
        fun label(suffix: String): String =
            listOf(source.track.trim(), suffix).filter { it.isNotBlank() }.joinToString(" • ")
        return listOf(
            SelcukFlixMediaFile(
                transformed,
                label("master playlist hypothesis"),
                quality
            ),
            SelcukFlixMediaFile(
                url,
                label("raw PHP fallback"),
                quality
            )
        )
    }

    fun subtitles(text: String, baseUrl: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val found = linkedMapOf<String, Pair<String, String>>()
        val document = Jsoup.parse(normalizeEscapes(text), baseUrl)
        document.select("track[src]").forEach { track ->
            val url = SelcukFlixResolverBank.normalizeUrl(track.attr("src"), baseUrl) ?: return@forEach
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            if (isSubtitle(url)) found.putIfAbsent(url, label to url)
        }
        Regex(
            """(?is)["']file["']\s*:\s*["']([^"']+\.(?:vtt|srt|ass|ssa)(?:\?[^"']*)?)["'][^{}]*["']label["']\s*:\s*["']([^"']*)["']"""
        ).findAll(normalizeEscapes(text)).forEach { match ->
            val url = SelcukFlixResolverBank.normalizeUrl(match.groupValues[1], baseUrl) ?: return@forEach
            val label = match.groupValues[2].ifBlank { "Altyazı" }
            found.putIfAbsent(url, label to url)
        }
        return found.values.toList()
    }

    private fun isSubtitle(url: String): Boolean =
        Regex("""(?i)\.(vtt|srt|ass|ssa|ttml)(?:$|[?#])""").containsMatchIn(url)

    private fun normalizeEscapes(value: String): String =
        value.replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
}

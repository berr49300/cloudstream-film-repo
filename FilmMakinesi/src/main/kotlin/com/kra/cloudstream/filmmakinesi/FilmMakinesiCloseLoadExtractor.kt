/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * CloseLoad resolver for FilmMakinesi.
 *
 * This resolver extracts only media URLs directly exposed in player HTML.
 * It does not implement external obfuscated-player decoding algorithms.
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.parser.Parser
import java.net.URI

open class FilmMakinesiCloseLoadExtractor : ExtractorApi() {
    open val playerLabel = "CloseLoad"
    override open val name: String get() = "FilmMakinesi • $playerLabel"
    override open val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        val pageRef = referer ?: "https://filmmakinesi.to/"
        val headers = mapOf("User-Agent" to userAgent, "Referer" to pageRef)
        val response = runCatching { app.get(url, headers = headers) }.getOrNull() ?: return
        val html = response.text

        FilmMakinesiSubtitleParser.parse(html, url).forEach { track ->
            subtitleCallback(newSubtitleFile(track.lang, track.url) {
                this.headers = headers
            })
        }

        for (stream in extractStreamUrls(html, url)) {
            callback(newExtractorLink(
                source = name,
                name = "$playerLabel • " + mediaLabelForUrl(stream),
                url = stream,
                type = mediaTypeForUrl(stream)
            ) {
                this.referer = url
                this.headers = headers
            })
        }
    }

    companion object {
        private val fileField = Regex(
            """(?is)["']?(?:file|src|source|url)["']?\s*[:=]\s*["']([^"']+)["']"""
        )
        private val mediaUrlPattern = Regex("""(?i)(?:https?:)?//[^"'\s<>]+""")
        internal fun extractStreamUrls(html: String, baseUrl: String): List<String> {
            val normalized = normalize(html)
            val result = linkedSetOf<String>()

            fun add(raw: String?) {
                if (raw.isNullOrBlank()) return
                val decoded = normalize(raw.trim().trim('\'', '"'))
                val absolute = resolveUrl(decoded, baseUrl) ?: return
                if (isMediaUrl(absolute)) result.add(absolute)
            }

            val decodedScripts = FilmMakinesiJsPacker.unpackAll(normalized)
            val candidateTexts = listOf(normalized) + decodedScripts
            for (candidate in candidateTexts) {
                fileField.findAll(candidate).forEach { add(it.groupValues.getOrNull(1)) }
                mediaUrlPattern.findAll(candidate).forEach { match ->
                    add(match.value.trimEnd(')', ']', '}', ',', ';'))
                }
            }

            // Only return media URLs explicitly present in the player HTML.
            // Obfuscated player payloads are intentionally not decoded here.

            return result.toList()
        }

        internal fun mediaTypeForUrl(url: String): ExtractorLinkType {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return when {
                path.endsWith(".mpd") -> ExtractorLinkType.DASH
                path.endsWith(".m3u8") || path.endsWith("/txt/master.txt") ||
                    path.endsWith("/master.txt") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
        }

        internal fun mediaLabelForUrl(url: String): String {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return when {
                path.endsWith(".m3u8") || path.endsWith("/txt/master.txt") ||
                    path.endsWith("/master.txt") -> "HLS"
                path.endsWith(".mpd") -> "DASH"
                path.endsWith(".mkv") -> "MKV"
                path.endsWith(".mp4") -> "MP4"
                path.endsWith(".webm") -> "WebM"
                else -> "Video"
            }
        }

        internal fun isMediaUrl(url: String): Boolean {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                path.endsWith(".mp4") || path.endsWith(".mkv") ||
                path.endsWith(".webm") || path.endsWith(".m4v") ||
                path.endsWith(".mov") || path.endsWith(".avi") ||
                path.endsWith(".ogv") || path.endsWith(".m3u") ||
                path.endsWith("/txt/master.txt") || path.endsWith("/master.txt")
        }

        private fun resolveUrl(raw: String, baseUrl: String): String? = runCatching {
            val normalized = normalize(raw).trim()
            if (normalized.isBlank() || normalized.startsWith("javascript:", true)) return null
            val resolved = URI(baseUrl).resolve(URI(normalized))
            if (resolved.scheme !in setOf("http", "https")) return null
            resolved.toASCIIString()
        }.getOrNull()

        private fun normalize(value: String): String = Parser.unescapeEntities(value, false)
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
    }
}

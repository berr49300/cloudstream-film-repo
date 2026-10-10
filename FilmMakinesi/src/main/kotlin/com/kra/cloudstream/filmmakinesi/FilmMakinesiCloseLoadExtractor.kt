/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * FilmMakinesi player resolver. The parsing stages are intentionally separated:
 * HTML entity/escape normalization, P.A.C.K.E.R. unpacking, player-payload
 * reconstruction, and finally conservative media URL extraction.
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
        val playerOrigin = runCatching { URI(url).let { "${it.scheme}://${it.host}" } }
            .getOrDefault(mainUrl)
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: "https://filmmakinesi.to/")
        )
        val html = runCatching { app.get(url, headers = headers).text }.getOrNull() ?: return

        FilmMakinesiSubtitleParser.parse(html, url).forEach { track ->
            subtitleCallback(newSubtitleFile(track.lang, track.url) { this.headers = headers })
        }

        for (stream in extractStreamUrls(html, url, playerLabel)) {
            callback(newExtractorLink(
                source = name,
                name = "$playerLabel • " + mediaLabelForUrl(stream),
                url = stream,
                type = mediaTypeForUrl(stream)
            ) {
                this.referer = playerOrigin
                this.headers = headers
            })
        }
    }

    companion object {
        private val fileField = Regex(
            """(?is)["']?(?:file|src|source|url|hls|mp4)["']?\s*[:=]\s*["']([^"']+)["']"""
        )
        private val mediaUrlPattern = Regex("""(?i)(?:https?:)?//[^"'\s<>\\]+""")
        private val packedPayload = Regex(
            """(?is)(?:var|let|const)\s+[\w$]+\s*=\s*[\w$]+\s*\(\s*["']([^"']{80,})["']\s*\.split\(\s*["']([|^*@#~])["']\s*\)\s*\)"""
        )
        private val keyPair = Regex(
            """(?is)(?:var|let|const)\s+[\w$]+\s*=\s*["']([^"']+)["']\s*;\s*(?:var|let|const)\s+[\w$]+\s*=\s*["']([^"']+)["']"""
        )
        private val arrayString = Regex(""""([^"]+)"""")

        fun extractStreamUrls(html: String, baseUrl: String, player: String = "CloseLoad"): List<String> {
            val normalized = normalize(html)
            val candidates = mutableListOf(normalized)
            candidates += FilmMakinesiJsPacker.unpackAll(normalized)

            // The site's Rapid endpoint has also served the CloseLoad-style
            // payload; run the shared path first, then Rapid-specific formats.
            closeLoadDecodedCandidates(normalized).forEach(candidates::add)
            if (player.equals("Rapid", ignoreCase = true)) {
                rapidDecodedCandidates(normalized).forEach(candidates::add)
            }

            val result = linkedSetOf<String>()
            for (candidate in candidates) {
                fileField.findAll(candidate).forEach { match ->
                    addMedia(result, match.groupValues.getOrNull(1), baseUrl)
                }
                mediaUrlPattern.findAll(candidate).forEach { match ->
                    addMedia(result, match.value.trimEnd(')', ']', '}', ',', ';'), baseUrl)
                }
            }
            return result.toList()
        }

        private fun closeLoadDecodedCandidates(html: String): List<String> {
            val decoded = mutableListOf<String>()
            val keys = keyPair.find(html)?.groupValues
            if (keys != null && keys.size >= 3) {
                val keyA = keys[1]
                val keyB = keys[2]
                val arrays = Regex("""(?s)\[(?:\s*["'][^"']*["']\s*,){8,}\s*["'][^"']*["']\s*\]""")
                for (array in arrays.findAll(html).take(8)) {
                    val joined = arrayString.findAll(array.value)
                        .joinToString("") { it.groupValues[1] }
                    decodeCloseLoad(joined, keyA, keyB).takeIf(String::isNotBlank)?.let(decoded::add)
                }
            }

            for (match in packedPayload.findAll(html)) {
                val chunks = match.groupValues[1].split(match.groupValues[2])
                if (chunks.size >= 10) {
                    val keyValues = keyPair.find(html)?.groupValues
                    if (keyValues != null && keyValues.size >= 3) {
                        decodeCloseLoad(chunks.joinToString(""), keyValues[1], keyValues[2])
                            .takeIf(String::isNotBlank)?.let(decoded::add)
                    }
                }
            }
            return decoded
        }

        /*
         * Reconstructs the player payload from its two runtime keys. This is a
         * bounded text transform only; decoded JavaScript is never evaluated.
         */
        private fun decodeCloseLoad(input: String, keyA: String, keyB: String): String {
            if (input.isEmpty() || keyA.isEmpty() || keyB.isEmpty()) return ""
            var text = input
            var seedA = 0
            var seedB = 0
            for (i in keyA.indices) {
                val ch = keyA[i].code
                seedA = (seedA * 31 + ch) % 251
                seedB = (seedB xor (ch + i)) and 255
            }
            val initial = (seedA + seedB) % 256
            val stride = (seedA % 13) + 3
            var state = ((seedA * 256 + seedB) % 65521) + 1

            for (i in keyB.indices.reversed()) {
                val op = keyB[i]
                text = when (op) {
                    'b' -> decodeBase64Latin1(text)
                    'v' -> text.reversed()
                    else -> rotateLetters(text, (26 - ((op.code - 64) % 26)) % 26)
                }
                if (text.isEmpty()) return ""
            }

            if (text.length > 200_000) return ""
            val swaps = IntArray(text.length)
            for (i in text.length - 1 downTo 1) {
                state = (state * 75 + 74) % 65537
                swaps[i] = state % (i + 1)
            }
            val chars = text.toCharArray()
            for (i in 1 until chars.size) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }

            var rolling = initial
            val output = StringBuilder(chars.size)
            for (ch in chars) {
                val code = ch.code
                rolling = (rolling + stride) % 256
                output.append((code xor rolling).toChar())
                rolling = (rolling + code) % 256
            }
            return output.toString()
        }

        private fun rapidDecodedCandidates(html: String): List<String> {
            val output = mutableListOf<String>()
            val tokenPatterns = listOf(
                Regex("""(?is)window\s*\.\s*_p8\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""(?is)["']?file["']?\s*:\s*av\(\s*['"]([^'"]+)['"]\s*\)""")
            )
            for (pattern in tokenPatterns) {
                for (match in pattern.findAll(html)) {
                    val decrypted = decryptRapidToken(match.groupValues[1])
                    if (decrypted.isNotBlank()) output += decrypted
                }
            }
            return output
        }

        private fun decryptRapidToken(token: String): String {
            return runCatching {
                val reversed = token.reversed()
                val outer = decodeBase64(reversed) ?: return ""
                val intermediate = String(outer, Charsets.ISO_8859_1)
                val key = "K9L"
                val shifted = buildString(intermediate.length) {
                    intermediate.forEachIndexed { index, ch ->
                        append((ch.code - (key[index % key.length].code % 5 + 1)).toChar())
                    }
                }
                val inner = decodeBase64(shifted) ?: return ""
                String(inner, Charsets.UTF_8)
            }.getOrDefault("")
        }

        private fun decodeBase64(value: String): ByteArray? {
            val padded = value + "=".repeat((4 - value.length % 4) % 4)
            return runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }
                .recoverCatching { android.util.Base64.decode(padded, android.util.Base64.URL_SAFE) }
                .getOrNull()
        }

        private fun decodeBase64Latin1(value: String): String {
            val bytes = decodeBase64(value) ?: return ""
            return String(bytes, Charsets.ISO_8859_1)
        }

        private fun rotateLetters(value: String, shift: Int): String = buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    in 'A'..'Z' -> append(((ch.code - 65 + shift) % 26 + 65).toChar())
                    in 'a'..'z' -> append(((ch.code - 97 + shift) % 26 + 97).toChar())
                    else -> append(ch)
                }
            }
        }

        private fun addMedia(result: MutableSet<String>, raw: String?, baseUrl: String) {
            if (raw.isNullOrBlank()) return
            val candidate = normalize(raw.trim().trim('\'', '"'))
            val absolute = resolveUrl(candidate, baseUrl) ?: return
            if (isMediaUrl(absolute)) result.add(absolute)
        }

        internal fun mediaTypeForUrl(url: String): ExtractorLinkType {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return when {
                path.endsWith(".mpd") -> ExtractorLinkType.DASH
                path.endsWith(".m3u8") || path.endsWith("/txt/master.txt") || path.endsWith("/master.txt") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
        }

        internal fun mediaLabelForUrl(url: String): String {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return when {
                path.endsWith(".m3u8") || path.endsWith("/txt/master.txt") || path.endsWith("/master.txt") -> "HLS"
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
                path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
                path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".avi") ||
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

/** Rapid has its own token format; it must not be treated as CloseLoad. */
class FilmMakinesiRapidExtractor : FilmMakinesiCloseLoadExtractor() {
    override val playerLabel = "Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
}

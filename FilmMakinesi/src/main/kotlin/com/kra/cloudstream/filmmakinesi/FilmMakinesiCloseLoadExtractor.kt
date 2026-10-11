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
import org.jsoup.Jsoup
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

        val subtitleTracks = linkedMapOf<String, Pair<String, String>>()
        FilmMakinesiSubtitleParser.parse(html, url).forEach { track ->
            subtitleTracks.putIfAbsent(track.url, track.lang to track.url)
        }
        if (playerLabel.equals("Rapid", ignoreCase = true)) {
            FilmMakinesiStreamParser.extractRapidSubtitleTracks(html, url).forEach { track ->
                subtitleTracks.putIfAbsent(track.second, track)
            }
        }
        subtitleTracks.values.forEach { (lang, subtitleUrl) ->
            subtitleCallback(newSubtitleFile(lang, subtitleUrl) { this.headers = headers })
        }

        for (stream in extractStreamUrls(html, url, playerLabel)) {
            callback(newExtractorLink(
                source = name,
                name = "$playerLabel • " + mediaLabelForUrl(stream),
                url = stream,
                type = if (
                    playerLabel.equals("Rapid", ignoreCase = true) &&
                    !FilmMakinesiStreamParser.isMediaUrl(stream)
                ) ExtractorLinkType.M3U8 else mediaTypeForUrl(stream)
            ) {
                this.referer = playerOrigin
                this.headers = headers
            })
        }
    }

    companion object {
        fun extractStreamUrls(html: String, baseUrl: String, player: String = "CloseLoad"): List<String> =
            FilmMakinesiStreamParser.extractStreamUrls(html, baseUrl, player)

        internal fun mediaTypeForUrl(url: String): ExtractorLinkType {
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            return when {
                path.endsWith(".mpd") -> ExtractorLinkType.DASH
                path.endsWith(".m3u8") || path.endsWith("/txt/master.txt") || path.endsWith("/master.txt") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
        }

        internal fun mediaLabelForUrl(url: String): String = FilmMakinesiStreamParser.mediaLabelForUrl(url)

        internal fun isMediaUrl(url: String): Boolean = FilmMakinesiStreamParser.isMediaUrl(url)
    }
}


internal object FilmMakinesiStreamParser {
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
            val result = linkedSetOf<String>()

            // The site's Rapid endpoint has also served the CloseLoad-style
            // payload; run the shared path first, then Rapid-specific formats.
            closeLoadDecodedCandidates(normalized).forEach { decoded ->
                candidates.add(decoded)
                // The reference resolver accepts decoded HTTP URLs even when
                // their path is opaque and has no recognizable media suffix.
                if (decoded.trimStart().startsWith("http", ignoreCase = true)) {
                    addCloseLoadStream(result, decoded.trim(), baseUrl)
                }
            }
            val rapidPayloads = if (player.equals("Rapid", ignoreCase = true)) {
                rapidDecodedCandidates(normalized).also(candidates::addAll)
            } else {
                emptyList()
            }

            // Match the upstream CloseLoad flow before extension-based URL scanning.
            extractCloseLoadStreamUrl(normalized, baseUrl)?.let {
                addCloseLoadStream(result, it, baseUrl)
            }
            runCatching {
                val doc = Jsoup.parse(normalized, baseUrl)
                val videoSource = doc.selectFirst("video source[src], source[src]")?.absUrl("src")
                    ?: doc.selectFirst("video[src]")?.absUrl("src")
                if (!videoSource.isNullOrBlank()) addCloseLoadStream(result, videoSource, baseUrl)
            }
            // Rapid's current JSON payload exposes cm/tm HLS endpoints, which
            // may not end in .m3u8. Treat those named fields as stream URLs.
            for (payload in rapidPayloads) {
                val decodedPayload = normalize(payload)
                if (decodedPayload.trimStart().startsWith("https://", true) ||
                    decodedPayload.trimStart().startsWith("http://", true)) {
                    addRapidStream(result, decodedPayload.trim(), baseUrl)
                }
                rapidStreamField.findAll(decodedPayload).forEach { match ->
                    addRapidStream(result, match.groupValues[1], baseUrl)
                }
            }
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

        /**
         * Port of the working CloseLoad extractor's ordered URL discovery:
         * direct file fields, variable-backed source payload, then generic
         * delimiter-packed payload. Opaque URLs are kept even without an
         * .m3u8/.mp4 suffix because providers sometimes use tokenized paths.
         */
        private fun extractCloseLoadStreamUrl(html: String, baseUrl: String): String? {
            val directFile = Regex(
                """(?is)file\s*:\s*["']([^"']+\.(?:m3u8|mp4|txt)(?:\?[^"']*)?)["']"""
            ).find(html)?.groupValues?.getOrNull(1)
            if (!directFile.isNullOrBlank()) return resolveUrl(directFile, baseUrl)

            val unpacked = if (html.contains("eval(function(p,a,c,k,e,d)")) {
                html + "\n" + FilmMakinesiJsPacker.unpackAll(html).joinToString("\n")
            } else html

            val varName = Regex(
                """(?is)sources\s*:\s*\[\s*\{\s*file\s*:\s*([a-zA-Z0-9_$]+)"""
            ).findAll(unpacked).map { it.groupValues[1] }.firstOrNull { it != "atob" }
            if (!varName.isNullOrBlank()) {
                val payloadPattern = Regex(
                    """(?is)(?:var|let|const)\s+$varName\s*=\s*[a-zA-Z0-9_$]+\s*\(\s*["']([^"']+)["']\s*\.split\(\s*["']([^"']+)["']\s*\)\s*\)"""
                )
                val match = payloadPattern.find(unpacked)
                if (match != null) {
                    val decoded = decodeCloseLoadPayload(
                        match.groupValues[1].split(match.groupValues[2])
                    )
                    if (decoded.startsWith("http", ignoreCase = true)) {
                        resolveUrl(decoded, baseUrl)?.let { return it }
                    }
                }
            }

            val genericPayload = Regex(
                """(?is)(?:var|let|const)\s+[a-zA-Z0-9_$]+\s*=\s*[a-zA-Z0-9_$]+\s*\(\s*["']([^"']{100,})["']\s*\.split\(\s*["']([|^*@#~])["']\s*\)\s*\)"""
            )
            for (match in genericPayload.findAll(unpacked)) {
                val parts = match.groupValues[1].split(match.groupValues[2])
                if (parts.size < 10) continue
                val decoded = decodeCloseLoadPayload(parts)
                if (decoded.startsWith("http", ignoreCase = true)) {
                    resolveUrl(decoded, baseUrl)?.let { return it }
                }
            }
            return null
        }

        private fun addCloseLoadStream(result: MutableSet<String>, raw: String, baseUrl: String) {
            val value = normalize(raw).trim().removeSurrounding("\"").removeSurrounding("'")
            val absolute = resolveUrl(value, baseUrl) ?: return
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return
            val host = uri.host.orEmpty().lowercase()
            if (uri.scheme !in setOf("http", "https") || host.isBlank()) return
            if (FilmMakinesiSourceRules.isYouTubeHost(host)) return
            if (host.contains("google") || host.contains("facebook") || host.contains("doubleclick")) return
            result.add(absolute)
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
                    // Legacy CloseLoad payloads use a separate list/key transform.
                    decodeCloseLoadPayload(chunks)
                        .takeIf(String::isNotBlank)?.let(decoded::add)
                }
            }
            return decoded
        }

        /*
         * Reconstructs the player payload from its two runtime keys. This is a
         * bounded text transform only; decoded JavaScript is never evaluated.
         */
        private fun decodeCloseLoad(input: String, keyA: String, keyB: String): String {
            if (input.isEmpty() || keyA.isEmpty() || keyB.isEmpty() || input.length > 200_000) return ""
            var text = input
            var seed = 0
            var mask = 0
            for (index in keyA.indices) {
                val code = keyA[index].code
                seed = (seed * 31 + code) % 251
                mask = (mask xor (code + index)) and 255
            }
            val initial = (seed + mask) % 256
            val stride = (seed % 13) + 3
            var state = ((seed * 256 + mask) % 65521) + 1

            for (index in keyB.indices.reversed()) {
                val operation = keyB[index]
                text = when (operation) {
                    'b' -> decodeBase64Latin1(text)
                    'v' -> text.reversed()
                    else -> rotateLetters(text, (26 - ((operation.code - 64) % 26)) % 26)
                }
                if (text.isEmpty()) return ""
            }
            if (text.length > 200_000) return ""

            val swaps = IntArray(text.length)
            for (index in text.length - 1 downTo 1) {
                state = (state * 75 + 74) % 65537
                swaps[index] = state % (index + 1)
            }
            val chars = text.toCharArray()
            for (index in 1 until chars.size) {
                val other = swaps[index]
                val temp = chars[index]
                chars[index] = chars[other]
                chars[other] = temp
            }

            var rolling = initial
            val decoded = StringBuilder(chars.size)
            for (ch in chars) {
                val code = ch.code
                rolling = (rolling + stride) % 256
                decoded.append((code xor rolling).toChar())
                rolling = (rolling + code) % 256
            }
            return decoded.toString()
        }

        private val rapidStreamField = Regex(
            """(?is)["'](?:cm|tm)["']\s*:\s*["']((?:\\.|[^"'])+)["']"""
        )

        private fun addRapidStream(result: MutableSet<String>, raw: String, baseUrl: String) {
            val normalized = normalize(raw)
                .replace("\\\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\u003a", ":", ignoreCase = true)
                .trim()
            val absolute = resolveUrl(normalized, baseUrl) ?: return
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return
            val host = uri.host.orEmpty().lowercase()
            if (host.isBlank() || FilmMakinesiSourceRules.isYouTubeHost(host)) return
            if (host.contains("google") || host.contains("facebook") || host.contains("doubleclick")) return
            result.add(absolute)
        }

        private val rapidCaptionObject = Regex(
            """(?is)\{[^{}]*"file"\s*:\s*"((?:\\.|[^"])*)"[^{}]*"label"\s*:\s*"((?:\\.|[^"])*)"[^{}]*}"""
        )

        internal fun extractRapidSubtitleTracks(html: String, baseUrl: String): List<Pair<String, String>> {
            val tracks = linkedMapOf<String, Pair<String, String>>()
            rapidDecodedCandidates(normalize(html)).forEach { payload ->
                rapidCaptionObject.findAll(payload).forEach { match ->
                    val rawUrl = normalize(match.groupValues[1])
                    val subtitleUrl = resolveUrl(rawUrl, baseUrl) ?: return@forEach
                    val label = normalize(match.groupValues[2])
                        .replace("\\\"", "\"")
                        .ifBlank { "Türkçe" }
                    tracks.putIfAbsent(subtitleUrl, label to subtitleUrl)
                }
            }
            return tracks.values.toList()
        }

        /*
         * Legacy CloseLoad payload variant: the delimiter-separated array
         * carries two transform keys, while the remaining parts form the
         * encoded body. This is a bounded text decoder and never evaluates JS.
         */
        private fun decodeCloseLoadPayload(parts: List<String>): String {
            if (parts.size < 10 || parts.size > 10_000 || parts.any { it.length > 100_000 }) return ""
            val values = parts.toMutableList()
            val offset = values.size - 2
            val seedIndex = offset % 7
            val operationIndex = 8 + (offset % 5)
            if (operationIndex >= values.size || seedIndex >= values.size) return ""

            val operations = values.removeAt(operationIndex)
            val seedKey = values.removeAt(seedIndex)
            var text = values.joinToString("")
            if (seedKey.length > 4096) text = decodeBase64Latin1(text)
            if (text.isEmpty() || text.length > 200_000) return ""

            var seed = 0
            var mask = 0
            for (index in seedKey.indices) {
                val code = seedKey[index].code
                seed = (seed * 37 + code) % 241
                mask = (mask + ((code shl 1) xor index)) and 255
            }
            val initial = (seed * 3 + mask) % 256
            val stride = (mask % 11) + 5
            var state = ((mask * 251 + seed) % 65519) + 1

            for (index in operations.indices.reversed()) {
                text = when (val operation = operations[index]) {
                    '7' -> decodeBase64Latin1(text)
                    '3' -> text.reversed()
                    else -> rotateLetters(text, (26 - ((operation.code - 96) % 26)) % 26)
                }
                if (text.isEmpty()) return ""
            }
            if (operations.length > 2048) text = text.reversed()
            if (text.length > 200_000) return ""

            val swaps = IntArray(text.length)
            for (index in text.length - 1 downTo 1) {
                state = (state * 97 + 41) % 65519
                swaps[index] = state % (index + 1)
            }
            val chars = text.toCharArray()
            for (index in 1 until chars.size) {
                val other = swaps[index]
                val temp = chars[index]
                chars[index] = chars[other]
                chars[other] = temp
            }

            var rolling = initial
            val decoded = StringBuilder(chars.size)
            for (ch in chars) {
                val code = ch.code
                rolling = (rolling * 5 + stride) % 256
                decoded.append((code xor rolling).toChar())
                rolling = (rolling + code) % 256
            }
            return decoded.toString()
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

        /**
         * Small JVM/Android-compatible Base64 decoder. Avoids linking the
         * android.util.Base64 stub into local JVM unit tests while supporting
         * standard and URL-safe alphabets on API 21+.
         */
        private fun decodeBase64(value: String): ByteArray? {
            val input = value.filterNot(Char::isWhitespace).trimEnd('=')
            if (input.isEmpty()) return byteArrayOf()
            if (input.length % 4 == 1) return null

            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val output = java.io.ByteArrayOutputStream(input.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (ch in input) {
                val normalized = when (ch) {
                    '-' -> '+'
                    '_' -> '/'
                    else -> ch
                }
                val digit = alphabet.indexOf(normalized)
                if (digit < 0) return null
                buffer = (buffer shl 6) or digit
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output.write((buffer shr bits) and 0xff)
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            return output.toByteArray()
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
/** Rapid has its own token format; it must not be treated as CloseLoad. */
class FilmMakinesiRapidExtractor : FilmMakinesiCloseLoadExtractor() {
    override val playerLabel = "Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
}

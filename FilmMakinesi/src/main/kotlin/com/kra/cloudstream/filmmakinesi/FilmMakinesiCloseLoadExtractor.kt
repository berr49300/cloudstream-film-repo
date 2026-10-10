/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * CloseLoad resolver for FilmMakinesi. Decoder strategy was cross-checked against
 * the public GPL-3.0 CloudStreamHub reference; see THIRD_PARTY_NOTICES.md.
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

class FilmMakinesiCloseLoadExtractor : ExtractorApi() {
    override val name = "FilmMakinesi • CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
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
                source = "FilmMakinesi • CloseLoad",
                name = mediaLabelForUrl(stream),
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
        private val variablePayload = Regex(
            """(?is)var\s+[\w$]+\s*=\s*[\w$]+\s*\(\s*["']([^"']{80,})["']\.split\(\s*["']([^"']+)["']\s*\)\s*\)"""
        )
        private val quotedArray = Regex("""\[[^\[\]]{80,20000}\]""")
        private val quotedString = Regex(""""([^"]+)"""")

        internal fun extractStreamUrls(html: String, baseUrl: String): List<String> {
            val normalized = normalize(html)
            val result = linkedSetOf<String>()

            fun add(raw: String?) {
                if (raw.isNullOrBlank()) return
                val decoded = normalize(raw.trim().trim('\'', '"'))
                val absolute = resolveUrl(decoded, baseUrl) ?: return
                if (isMediaUrl(absolute)) result.add(absolute)
            }

            fileField.findAll(normalized).forEach { add(it.groupValues.getOrNull(1)) }
            mediaUrlPattern.findAll(normalized).forEach { match ->
                add(match.value.trimEnd(')', ']', '}', ',', ';'))
            }

            val unpacked = unpackPacker(normalized)
            if (!unpacked.isNullOrBlank()) {
                fileField.findAll(unpacked).forEach { add(it.groupValues.getOrNull(1)) }
                mediaUrlPattern.findAll(unpacked).forEach { add(it.value.trimEnd(')', ']', '}', ',', ';')) }
            }

            // Newer CloseLoad scripts reference a variable populated by a split-string decoder.
            val code = unpacked ?: normalized
            val sourceVariable = Regex(
                """(?is)sources\s*:\s*\[\s*\{\s*file\s*:\s*([\w$]+)"""
            ).find(code)?.groupValues?.getOrNull(1)
            if (!sourceVariable.isNullOrBlank()) {
                val sourcePattern = Regex(
                    """(?is)var\s+""" + Regex.escape(sourceVariable) +
                        """\s*=\s*[\w$]+\s*\(\s*["']([^"']+)["']\.split\(\s*["']([^"']+)["']\s*\)\s*\)"""
                )
                sourcePattern.find(code)?.let { match ->
                    val parts = match.groupValues[1].split(match.groupValues[2])
                    add(decodeCloseLoadPayload(parts))
                }
            }

            variablePayload.findAll(code).forEach { match ->
                val parts = match.groupValues[1].split(match.groupValues[2])
                add(decodeCloseLoadPayload(parts))
            }

            // Older player revisions pass an array of encoded chunks into a function.
            // Limit attempts so unrelated script arrays cannot cause unbounded work.
            var attempts = 0
            for (array in quotedArray.findAll(code)) {
                if (attempts++ >= 20) break
                add(decodeLegacyCloseLoad(code, array.value))
            }

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

        private fun decodeCloseLoadPayload(originalParts: List<String>): String {
            if (originalParts.size < 10) return ""
            val parts = originalParts.toMutableList()
            val removalIndex = parts.size - 2
            val keyIndex = removalIndex % 7
            val modeIndex = 8 + (removalIndex % 5)
            if (modeIndex >= parts.size || keyIndex >= parts.size || modeIndex == keyIndex) return ""

            val mode = parts.removeAt(modeIndex)
            val seedText = parts.removeAt(keyIndex)
            var payload = parts.joinToString("")
            if (seedText.length > 4096) payload = decodeBase64Latin1(payload)

            var seedHash = 0
            var mixHash = 0
            seedText.forEachIndexed { index, char ->
                val code = char.code
                seedHash = (seedHash * 37 + code) % 241
                mixHash = (mixHash + ((code shl 1) xor index)) and 255
            }

            val xorSeed = (seedHash * 3 + mixHash) % 256
            val step = (mixHash % 11) + 5
            var randomState = ((mixHash * 251 + seedHash) % 65519) + 1

            for (char in mode.reversed()) {
                payload = when (char) {
                    '7' -> decodeBase64Latin1(payload)
                    '3' -> payload.reversed()
                    else -> rotateAsciiLetters(payload, (26 - ((char.code - 96) % 26)) % 26)
                }
            }
            if (mode.length > 2048) payload = payload.reversed()

            val shuffle = IntArray(payload.length)
            for (index in payload.length - 1 downTo 1) {
                randomState = (randomState * 97 + 41) % 65519
                shuffle[index] = randomState % (index + 1)
            }
            val shuffled = payload.toCharArray()
            for (index in 1 until shuffled.size) {
                val target = shuffle[index]
                val saved = shuffled[index]
                shuffled[index] = shuffled[target]
                shuffled[target] = saved
            }
            payload = String(shuffled)

            var rolling = xorSeed
            val output = StringBuilder(payload.length)
            for (char in payload) {
                val code = char.code
                rolling = (rolling * 5 + step) % 256
                output.append((code xor rolling).toChar())
                rolling = (rolling + code) % 256
            }
            return output.toString()
        }

        internal fun decodeLegacyCloseLoad(jsCode: String, arrayLiteral: String): String {
            val chunks = quotedString.findAll(arrayLiteral)
                .map { it.groupValues[1] }
                .joinToString("")
            if (chunks.isEmpty()) return ""

            val keys = Regex("""var\s+[\w$]+\s*=\s*["']([^"']+)["']\s*;""")
                .findAll(jsCode).map { it.groupValues[1] }.take(2).toList()
            if (keys.size < 2) return ""
            val keyA = keys[0]
            val keyB = keys[1]

            var rollingHash = 0
            var bitHash = 0
            keyA.forEachIndexed { index, char ->
                rollingHash = (rollingHash * 31 + char.code) % 251
                bitHash = (bitHash xor (char.code + index)) and 255
            }
            val xorSeed = (rollingHash + bitHash) % 256
            val step = (rollingHash % 13) + 3
            var randomState = ((rollingHash * 256 + bitHash) % 65521) + 1
            var payload = chunks

            for (mode in keyB.reversed()) {
                payload = when (mode) {
                    'b' -> decodeBase64Latin1(payload)
                    'v' -> payload.reversed()
                    else -> rotateAsciiLetters(payload, (26 - ((mode.code - 64) % 26)) % 26)
                }
            }

            val shuffle = IntArray(payload.length)
            for (index in payload.length - 1 downTo 1) {
                randomState = (randomState * 75 + 74) % 65537
                shuffle[index] = randomState % (index + 1)
            }
            val shuffled = payload.toCharArray()
            for (index in 1 until shuffled.size) {
                val target = shuffle[index]
                val saved = shuffled[index]
                shuffled[index] = shuffled[target]
                shuffled[target] = saved
            }

            var rolling = xorSeed
            val output = StringBuilder(shuffled.size)
            for (char in shuffled) {
                val code = char.code
                rolling = (rolling + step) % 256
                output.append((code xor rolling).toChar())
                rolling = (rolling + code) % 256
            }
            return output.toString()
        }

        private fun rotateAsciiLetters(value: String, shift: Int): String {
            val normalizedShift = ((shift % 26) + 26) % 26
            return buildString(value.length) {
                value.forEach { char ->
                    when (char) {
                        in 'A'..'Z' -> append(((char.code - 'A'.code + normalizedShift) % 26 + 'A'.code).toChar())
                        in 'a'..'z' -> append(((char.code - 'a'.code + normalizedShift) % 26 + 'a'.code).toChar())
                        else -> append(char)
                    }
                }
            }
        }

        private fun decodeBase64Latin1(source: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val data = source.filter { it != '=' }.replace('-', '+').replace('_', '/')
            val bytes = ByteArrayOutputStream()
            var accumulator = 0
            var bits = 0
            for (char in data) {
                val value = alphabet.indexOf(char)
                if (value < 0) continue
                accumulator = (accumulator shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    bytes.write((accumulator shr bits) and 0xff)
                }
            }
            return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
        }

        private fun unpackPacker(text: String): String? = runCatching {
            val match = Regex(
                """\}\s*\(\s*'([\s\S]*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]*?)'\.split\('\|'\)"""
            ).find(text) ?: return null
            var payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val radix = match.groupValues[2].toInt()
            val count = match.groupValues[3].toInt()
            val words = match.groupValues[4].split('|')
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

            fun baseN(value: Int): String {
                if (value == 0) return "0"
                var n = value
                var output = ""
                while (n > 0) {
                    output = digits[n % radix] + output
                    n /= radix
                }
                return output
            }

            for (index in count - 1 downTo 0) {
                val word = words.getOrNull(index).orEmpty()
                if (word.isNotEmpty()) {
                    payload = Regex("\\b" + Regex.escape(baseN(index)) + "\\b").replace(payload, word)
                }
            }
            payload
        }.getOrNull()

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

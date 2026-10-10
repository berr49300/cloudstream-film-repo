/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Site/player subtitle parser for FilmMakinesi.
 */
package com.kra.cloudstream.filmmakinesi

import org.jsoup.parser.Parser
import java.net.URI

internal data class FilmMakinesiSubtitleTrack(
    val lang: String,
    val url: String
)

internal object FilmMakinesiSubtitleParser {
    private val subtitleExtension = Regex("""(?i)\.(?:vtt|srt|ass|ssa|ttml|xml)$""")

    fun parse(html: String, baseUrl: String): List<FilmMakinesiSubtitleTrack> {
        val normalized = normalize(html)
        val tracks = linkedMapOf<String, FilmMakinesiSubtitleTrack>()

        fun add(rawUrl: String?, rawLabel: String?, localContext: String = "") {
            if (rawUrl.isNullOrBlank()) return
            val url = absoluteUrl(rawUrl, baseUrl) ?: return
            val uri = runCatching { URI(url) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
            if (!subtitleExtension.containsMatchIn(uri.path.orEmpty())) return

            val label = decode(rawLabel.orEmpty()).trim()
            val trackName = normalizeLabel(label, url, localContext)
            tracks.putIfAbsent(url, FilmMakinesiSubtitleTrack(trackName, url))
        }

        val trackPattern = Regex("""(?is)<track\b([^>]+)>""")
        val attrPattern = Regex("""(?is)([\w:-]+)\s*=\s*["']([^"']*)["']""")
        for (match in trackPattern.findAll(normalized)) {
            val attrs = attrPattern.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val kind = attrs["kind"].orEmpty().lowercase()
            if (kind.isNotBlank() && kind !in setOf("subtitles", "captions")) continue
            add(
                attrs["src"] ?: attrs["data-src"] ?: attrs["data-url"],
                attrs["label"] ?: attrs["srclang"] ?: attrs["lang"],
                match.value
            )
        }

        // Player configs may store one {file,label} object per caption track.
        val filePattern = Regex("""(?is)["']?(?:file|src|url|subtitle|captions)["']?\s*[:=]\s*["']([^"']+)["']""")
        for (match in filePattern.findAll(normalized)) {
            val context = objectContext(normalized, match.range.first)
            val label = Regex("""(?is)["']?(?:label|srclang|language|lang)["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(context)?.groupValues?.getOrNull(1)
            add(match.groupValues.getOrNull(1), label, context)
        }

        // Catch standalone subtitle URLs even when the player has no named JSON object.
        val urlPattern = Regex(
            """(?i)(?:https?:)?//[^"'\s<>]+?\.(?:vtt|srt|ass|ssa|ttml|xml)(?:\?[^"'\s<>]*)?|/[^"'\s<>]+?\.(?:vtt|srt|ass|ssa|ttml|xml)(?:\?[^"'\s<>]*)?"""
        )
        for (match in urlPattern.findAll(normalized)) {
            val context = objectContext(normalized, match.range.first)
            val label = Regex("""(?is)["']?(?:label|srclang|language|lang)["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(context)?.groupValues?.getOrNull(1)
            add(match.value, label, context)
        }

        return tracks.values.sortedBy { priority(it.lang) }
    }

    internal fun normalizeLabel(label: String, url: String, context: String = ""): String {
        val raw = decode(label).trim()
        val languageEvidence = (raw + " " + url).lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        val normalizedLabel = raw.lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        val forced = Regex("""(?i)\bforced\b""").containsMatchIn(languageEvidence)
        val turkish = Regex(
            """(?i)(?:\btr\b|\btur\b|\bturkish\b|\bturkce\b|türkçe|turkçe|tur[-_.]|\btur[-_]?\d)"""
        ).containsMatchIn(languageEvidence)
        val english = Regex(
            """(?i)(?:\ben\b|\beng\b|\benglish\b|\bingilizce\b|en[-_](?:us|gb))"""
        ).containsMatchIn(languageEvidence)

        val base = when {
            turkish -> "Türkçe"
            english -> "İngilizce"
            raw.isNotBlank() && normalizedLabel !in setOf("subtitles", "captions", "default") -> raw
            else -> "Altyazı"
        }
        // Forced comes only from this track's label or URL, never from a broad neighboring snippet.
        return if (forced && !base.contains("forced", ignoreCase = true)) "$base Forced" else base
    }

    private fun priority(label: String): Int = when (label.lowercase()) {
        "türkçe" -> 0
        "ingilizce" -> 1
        "türkçe forced" -> 2
        else -> 3
    }

    private fun objectContext(text: String, position: Int): String {
        val start = text.lastIndexOf('{', position)
        if (start < 0) return text.substring((position - 120).coerceAtLeast(0), (position + 180).coerceAtMost(text.length))

        var depth = 0
        var quoted = false
        var quote = '\u0000'
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quoted = false
                continue
            }
            if (ch == '"' || ch == '\'') {
                quoted = true
                quote = ch
                continue
            }
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return text.substring(start, (position + 180).coerceAtMost(text.length))
    }

    private fun absoluteUrl(raw: String, baseUrl: String): String? = runCatching {
        val value = decode(raw.trim())
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
        if (value.isBlank() || value.startsWith("javascript:", true) || value.startsWith("data:", true)) return null
        val result = URI(baseUrl).resolve(URI(value))
        if (result.scheme !in setOf("http", "https")) return null
        result.toASCIIString()
    }.getOrNull()

    private fun normalize(text: String): String = text
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun decode(text: String): String {
        var decoded = text
        repeat(2) {
            decoded = Regex("""\\u([0-9a-fA-F]{4})""").replace(decoded) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            decoded = Regex("""\\x([0-9a-fA-F]{2})""").replace(decoded) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return Parser.unescapeEntities(decoded, false)
    }
}

package com.kra.cloudstream.dizilla

import java.net.URI

/**
 * Parses external subtitle tracks from player-page fragments without assuming
 * the site's JSON/JavaScript quote style. A menu label alone is not a playable
 * subtitle: URLs are normalized and resolved against the actual iframe page.
 */
internal data class ContentXSubtitle(val label: String, val url: String)

internal object ContentXSubtitleParser {
    private val objectPattern = Regex("""(?s)\{[^{}]*\}""")
    private val filePattern = Regex("""(?is)["']?file["']?\s*:\s*["']((?:\\.|[^"'\\])*)["']""")
    private val labelPattern = Regex("""(?is)["']?label["']?\s*:\s*["']((?:\\.|[^"'\\])*)["']""")

    fun parse(playerPage: String, pageUrl: String): List<ContentXSubtitle> {
        if (playerPage.isBlank()) return emptyList()

        val normalized = decodeEscapes(playerPage)
        val objects = objectPattern.findAll(normalized).map { it.value }.toList()
        val fragments = if (objects.isEmpty()) listOf(normalized) else objects
        val tracks = linkedMapOf<String, ContentXSubtitle>()

        for (fragment in fragments) {
            val rawFile = filePattern.find(fragment)?.groupValues?.getOrNull(1) ?: continue
            val rawLabel = labelPattern.find(fragment)?.groupValues?.getOrNull(1) ?: continue
            val label = decodeEscapes(rawLabel).trim()
            if (label.isBlank()) continue
            val url = resolveHttpUrl(decodeEscapes(rawFile), pageUrl) ?: continue
            tracks.putIfAbsent(url, ContentXSubtitle(label, url))
        }

        // Some legacy players expose a single flat file/label pair without braces.
        if (tracks.isEmpty()) {
            val rawFile = filePattern.find(normalized)?.groupValues?.getOrNull(1)
            val rawLabel = labelPattern.find(normalized)?.groupValues?.getOrNull(1)
            if (!rawFile.isNullOrBlank() && !rawLabel.isNullOrBlank()) {
                val url = resolveHttpUrl(decodeEscapes(rawFile), pageUrl)
                if (url != null) tracks[url] = ContentXSubtitle(decodeEscapes(rawLabel).trim(), url)
            }
        }
        return tracks.values.toList()
    }

    private fun resolveHttpUrl(rawValue: String, pageUrl: String): String? {
        val value = rawValue.trim().trim('"', '\'')
            .replace("&amp;", "&", ignoreCase = true)
        if (value.isBlank() || value.startsWith("javascript:", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true) || value.startsWith("blob:", ignoreCase = true)
        ) return null

        return runCatching {
            val absolute = URI(pageUrl).resolve(URI(value))
            if (absolute.scheme?.lowercase() !in setOf("http", "https") || absolute.host.isNullOrBlank()) {
                return null
            }
            absolute.toASCIIString()
        }.getOrNull()
    }

    private fun decodeEscapes(value: String): String {
        var decoded = value
            // Players commonly HTML-escape or JSON-escape their inline data.
            .replace(Regex("""\\+(['"])""")) { it.groupValues[1] }
            .replace(Regex("""\\+/"""), "/")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2f;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)

        decoded = Regex("""\\+u([0-9a-fA-F]{4})""").replace(decoded) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return decoded
    }
}

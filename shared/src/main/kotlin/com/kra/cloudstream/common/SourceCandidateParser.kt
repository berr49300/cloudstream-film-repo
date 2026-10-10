package com.kra.cloudstream.common

import org.jsoup.Jsoup
import java.net.URI

/**
 * Extracts common HTTP(S) source candidates from provider response fragments.
 *
 * This is intentionally a generic candidate parser, not a universal video-host resolver:
 * player-specific token, signature, or API logic belongs in a dedicated ExtractorApi.
 */
internal object SourceCandidateParser {
    private val propertyPattern = Regex(
        """(?i)["']?(?:src|url|iframe|embed|link|file|playurl|video_url)["']?\\s*[:=]\\s*["']([^"'<>]+)["']"""
    )

    private val plainUrlPattern = Regex("""(?i)^(?:https?:)?//[^\\s"'<>]+$""")

    fun extract(content: String?, baseUrl: String): List<String> {
        if (content.isNullOrBlank()) return emptyList()

        val decoded = decodeEscapes(content.trim())
        val candidates = linkedSetOf<String>()

        // A response may itself be a media/embed URL instead of an HTML fragment.
        if (plainUrlPattern.matches(decoded)) {
            normalize(decoded, baseUrl)?.let(candidates::add)
        }

        // Parse HTML first so entity-escaped and relative attributes are handled correctly.
        val document = Jsoup.parseBodyFragment(decoded, baseUrl)
        val elements = document.select(
            "iframe, frame, embed, object, video, source, audio, a, " +
                "[data-url], [data-link], [data-iframe], [data-embed], [data-file], [data-src]"
        )
        val attributes = listOf(
            "src", "data-src", "data-lazy-src", "data-url", "data-link",
            "data-iframe", "data-embed", "data-file", "href", "data"
        )

        elements.forEach { element ->
            attributes.forEach { attribute ->
                if (element.hasAttr(attribute)) {
                    normalize(element.attr(attribute), baseUrl)?.let(candidates::add)
                }
            }
        }

        // Some providers return a JSON/JavaScript fragment rather than valid HTML.
        propertyPattern.findAll(decoded).forEach { match ->
            normalize(match.groupValues[1], baseUrl)?.let(candidates::add)
        }

        return candidates.toList()
    }

    private fun decodeEscapes(value: String): String =
        value
            .replace("\\\\/", "/")
            .replace("\\\\\"", "\"")
            .replace("\\\\'", "'")
            .replace("\\\\u002f", "/", ignoreCase = true)
            .replace("\\\\u003a", ":", ignoreCase = true)
            .replace("\\\\u003d", "=", ignoreCase = true)
            .replace("\\\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\""", ignoreCase = true)
            .replace("&#x2f;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)

    private fun normalize(value: String, baseUrl: String): String? {
        val candidate = decodeEscapes(value.trim()).trim('"', '\'')
        if (candidate.isBlank() || candidate.startsWith("javascript:", ignoreCase = true)) return null
        if (candidate.startsWith("data:", ignoreCase = true) || candidate.startsWith("blob:", ignoreCase = true)) return null

        return runCatching {
            val parsed = URI(candidate)
            val resolved = if (parsed.isAbsolute) parsed else URI(baseUrl).resolve(
                if (candidate.startsWith("//")) URI("https:$candidate") else parsed
            )
            val scheme = resolved.scheme?.lowercase()
            if (scheme !in setOf("http", "https") || resolved.host.isNullOrBlank()) return null
            resolved.toASCIIString()
        }.getOrNull()
    }
}

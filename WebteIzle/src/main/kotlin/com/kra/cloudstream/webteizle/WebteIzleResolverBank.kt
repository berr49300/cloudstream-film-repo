package com.kra.cloudstream.webteizle

import org.jsoup.Jsoup
import java.net.URI

/**
 * Small WebteIzle-specific helper set. This provider intentionally supports VidMoly only.
 * It does not scan arbitrary scripts, try unrelated extractors, or bypass access challenges.
 */
internal object WebteIzleResolverBank {
    private val vidMolyHosts = setOf("vidmoly.to", "vidmoly.me", "vidmoly.net", "vidmoly.biz")

    internal fun isAccessChallenge(html: String): Boolean =
        Regex(
            """(?is)(cf-chl-|challenge-platform|/cdn-cgi/challenge-platform|verify you are human|just a moment\.\.\.|checking your browser|attention required!\s*\|\s*cloudflare|captcha|access denied|error 1020|error 1015)"""
        ).containsMatchIn(html)

    internal fun playbackLanguage(url: String): Pair<String, String>? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        return when {
            path.contains("/izle/dublaj/", ignoreCase = true) -> "0" to "Dublaj"
            path.contains("/izle/altyazi/", ignoreCase = true) -> "1" to "Altyazı"
            else -> null
        }
    }

    internal fun findPlaybackPageUrls(html: String, baseUrl: String, siteBaseUrl: String): List<String> {
        val expectedHost = runCatching { URI(siteBaseUrl).host?.lowercase() }.getOrNull() ?: return emptyList()
        return Jsoup.parse(html, baseUrl)
            .select("a[href*='/izle/altyazi/'], a[href*='/izle/dublaj/']")
            .mapNotNull { anchor ->
                val url = normalizeUrl(anchor.attr("href"), baseUrl) ?: return@mapNotNull null
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (uri.host?.lowercase() != expectedHost) return@mapNotNull null
                if (playbackLanguage(url) == null) return@mapNotNull null
                url
            }
            .distinct()
    }

    /**
     * Keep only observed VidMoly iframe URLs. If the site's raw AJAX fragment exposes the known
     * vidmoly('id', ...) call instead, make a short list of documented VidMoly embed hosts and try
     * them sequentially. The caller emits only the first playable result for the language.
     */
    internal fun findVidMolyPlayerUrls(html: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val doc = Jsoup.parse(html, baseUrl)
        doc.select("iframe[src], iframe[data-src]").forEach { frame ->
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            val url = normalizeUrl(raw, baseUrl) ?: return@forEach
            if (isVidMolyUrl(url)) result += url
        }
        val idRegex = Regex("""(?i)\bvidmoly\s*\(\s*['"]([A-Za-z0-9_-]{3,256})['"]""")
        idRegex.findAll(normalizeEscapes(html)).forEach { match ->
            val id = match.groupValues[1]
            listOf("biz", "net", "to", "me").forEach { suffix ->
                result += "https://vidmoly.$suffix/embed-$id.html"
            }
        }
        return result.toList()
    }

    internal fun isVidMolyUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
        return vidMolyHosts.any { host == it || host.endsWith(".$it") }
    }

    internal fun isYouTubeUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    internal fun extractVidMolyMasterPlaylist(html: String): String? {
        val normalized = normalizeEscapes(html)
        val raw = Regex("""(?is)\bfile\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .find(normalized)?.groupValues?.get(1) ?: return null
        val value = normalizeUrl(raw, "https://vidmoly.biz/") ?: return null
        if (isYouTubeUrl(value)) return null
        return value.takeIf {
            it.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        }
    }

    private fun normalizeUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val value = normalizeEscapes(raw.trim().trim('"', '\'')).trimEnd(';', ',')
        if (value.startsWith("blob:", true) || value.startsWith("data:", true)) return null
        return runCatching {
            val absolute = if (value.startsWith("//")) "https:$value"
                else if (value.startsWith("http://", true) || value.startsWith("https://", true)) value
                else URI(baseUrl).resolve(value).toString()
            absolute.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        }.getOrNull()
    }

    private fun normalizeEscapes(value: String): String = value
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
}

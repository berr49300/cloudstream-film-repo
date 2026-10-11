package com.kra.cloudstream.ddizi

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal object DDiziParser {
    fun isEpisodeUrl(url: String): Boolean =
        runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
            .startsWith("/izle/", ignoreCase = true)

    fun isSeriesUrl(url: String): Boolean =
        runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
            .startsWith("/diziler/", ignoreCase = true)

    /**
     * Remove episode/season decorations used by DDizi in both visible titles and URL slugs.
     * This intentionally keeps the show's own title (including digits such as "Daha 17").
     */
    fun cleanTitle(value: String): String = value
        .replace('-', ' ')
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*sezon\b.*$"""), "")
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*b[oö]l[uü]m\b.*$"""), "")
        .replace(Regex("""(?i)\s+(?:son\s+)?b[oö]l[uü]m\s+izle.*$"""), "")
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .trim()

    fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ı', 'i')
        .replace('ş', 's')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ö', 'o')
        .replace('ç', 'c')
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

    private fun titleIdentity(value: String): String =
        normalizeTitle(
            cleanTitle(value)
                .replace(Regex("""^\s*\d+\s*[.\-:)]\s*"""), "")
                .trim()
        )

    private fun sameTitle(expected: String, candidate: String): Boolean =
        candidate.isNotBlank() &&
            (candidate == expected || candidate.startsWith("$expected "))

    /**
     * The episode URL slug is more trustworthy than a surrounding card's text: DDizi
     * places unrelated "Bugünkü Bölümler" links on series pages, and malformed markup
     * can make Jsoup text include neighbouring content.
     */
    fun belongsToSeriesEpisode(seriesTitle: String, episodeTitle: String, episodeUrl: String? = null): Boolean {
        val expected = titleIdentity(seriesTitle)
        if (expected.isBlank()) return false

        val slugIdentity = episodeUrl?.let { url ->
            val segment = runCatching { URI(url).path.orEmpty().substringAfterLast('/') }
                .getOrDefault("")
                .substringBeforeLast('.')
                .replace('-', ' ')
            if (segment.any { it.isLetter() }) titleIdentity(segment) else ""
        }.orEmpty()

        if (slugIdentity.isNotBlank()) return sameTitle(expected, slugIdentity)
        return sameTitle(expected, titleIdentity(episodeTitle))
    }

    fun seriesUrlMatchesTitle(title: String, url: String): Boolean {
        if (!isSeriesUrl(url)) return false
        val expected = titleIdentity(title)
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val parts = path.split('/').filter { it.isNotBlank() }.toMutableList()
        if (parts.lastOrNull()?.matches(Regex("""(?i)sayfa-\d+""")) == true) parts.removeAt(parts.lastIndex)
        val slug = parts.lastOrNull().orEmpty().replace('-', ' ')
        return sameTitle(expected, titleIdentity(slug))
    }

    fun seasonNumber(value: String): Int? {
        val match = Regex(
            """(?i)(?:\bsezon\s*(\d+)\b|(\d+)\s*\.?\s*sezon\b)"""
        ).find(value) ?: return null
        return (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(2))?.toIntOrNull()
    }

    fun youtubeWatchUrlIfEmbed(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val idValue = queryParameter(uri.rawQuery, "id")

        // Historical relay still used by some episodes.
        if ((host == "ddizi.re" || host.endsWith(".ddizi.re")) &&
            path.contains("youtube.php", ignoreCase = true)
        ) {
            val decodedId = idValue ?: return null
            return youtubeWatchUrlFrom(decodedId)
                ?: decodedId.takeIf { isValidYoutubeId(it) }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
        }

        // Current DDizi /player/telif/ relay stores a complete YouTube URL in its id parameter.
        if ((host == "ddizi.tel" || host.endsWith(".ddizi.tel")) &&
            path.endsWith("/player/telif/index.php")
        ) {
            return youtubeWatchUrlFrom(idValue ?: return null)
        }
        return null
    }

    private fun queryParameter(rawQuery: String?, name: String): String? =
        rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() }

    private fun youtubeWatchUrlFrom(raw: String): String? {
        val candidate = raw.trim()
        val id = if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            when {
                host == "youtu.be" || host == "www.youtu.be" ->
                    uri.path.orEmpty().trim('/').substringBefore('/').takeIf { isValidYoutubeId(it) }
                host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                    when {
                        uri.path.equals("/watch", ignoreCase = true) ->
                            queryParameter(uri.rawQuery, "v")?.takeIf { isValidYoutubeId(it) }
                        else -> {
                            val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
                            val marker = parts.indexOfFirst { it.equals("embed", true) ||
                                it.equals("shorts", true) || it.equals("live", true) }
                            parts.getOrNull(marker + 1)?.takeIf { marker >= 0 && isValidYoutubeId(it) }
                        }
                    }
                }
                else -> null
            }
        } else {
            candidate.takeIf { isValidYoutubeId(it) }
        }
        return id?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private fun isValidYoutubeId(value: String): Boolean =
        Regex("""^[A-Za-z0-9_-]{6,32}$""").matches(value)

    fun episodeNumber(value: String): Int? =
        Regex("""(?i)(\d+)\s*\.?\s*b[oö]l[uü]m\b""")
            .find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)\bS\d+\s*E(\d+)\b""").find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)\b\d+\s*[x×]\s*(\d+)\b""").find(value)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * When DDizi does not state the season number, split only at a finale explicitly
     * labelled as such. Explicit "N. Sezon" markers take priority in the provider.
     */
    fun seasonForEpisode(episode: Int?, seasonFinalEpisodes: Collection<Int>): Int? {
        if (episode == null) return null
        return 1 + seasonFinalEpisodes.count { it < episode }
    }

    fun seasonFinalEpisodeNumbers(titles: Collection<String>): Set<Int> =
        titles.mapNotNull { title ->
            if (seasonNumber(title) != null || !title.contains("sezon final", ignoreCase = true)) null
            else episodeNumber(title)
        }.toSet()

    fun absolutePath(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault(url.lowercase(Locale.ROOT))
}

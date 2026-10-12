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

    fun isSeriesCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault(url)
        return path.endsWith("/yabanci-dizi-izle", true) ||
            path.endsWith("/eski.diziler", true)
    }

    /**
     * Strip DDizi's episode suffixes and SEO decorations.
     * Keep digits that belong to a series name, e.g. "Daha 17".
     */
    fun cleanTitle(value: String): String = value
        .replace('-', ' ')
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*(?:sezon|season)\b.*$"""), "")
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*(?:b[oö]l[uü]m|episode|ep)\b.*$"""), "")
        .replace(Regex("""(?i)\s+(?:son\s+)?b[oö]l[uü]m\s+izle.*$"""), "")
        // Series headers such as "Dark izle Türkçe Dublaj Tüm Bölümler HD"
        .replace(Regex("""(?i)\s+izle\b.*$"""), "")
        .replace(Regex("""(?i)\s+(?:t[uü]m\s+)?b[oö]l[uü]mler?(?:\s+hd)?\b.*$"""), "")
        .replace(Regex("""(?i)\s+(?:t[uü]rk[cç]e|dublaj|altyaz[iı]l[iı]|full|hd)\b.*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':', '·')

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
     * Prefer the episode slug as it names the actual series and avoids adjacent "today's
     * episodes" links. HTML labels remain a fallback for older URLs without a useful slug.
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
            """(?i)(?:\b(?:sezon|season)\s*(\d+)\b|(\d+)\s*\.?\s*(?:sezon|season)\b)"""
        ).find(value) ?: return null
        return (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(2))?.toIntOrNull()
    }

    fun youtubeWatchUrlIfEmbed(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val idValue = queryParameter(uri.rawQuery, "id") ?: queryParameter(uri.rawQuery, "url")

        // Normalize regular YouTube links and iframe URLs to the watch route before invoking
        // CloudStream's registered extractor; iframe URLs can otherwise expose unusable link sets.
        if (host == "youtu.be" || host == "www.youtu.be" ||
            host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
        ) {
            return youtubeWatchUrlFrom(url)
        }

        // Historical DDizi relay.
        if ((host == "ddizi.re" || host.endsWith(".ddizi.re")) &&
            path.contains("youtube.php", ignoreCase = true)
        ) {
            val candidate = idValue ?: return null
            return youtubeWatchUrlFrom(candidate)
                ?: candidate.takeIf { isValidYoutubeId(it) }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
        }

        // DDizi's same-site relay may wrap a full URL or just a video ID.
        if ((host == "ddizi.tel" || host.endsWith(".ddizi.tel")) &&
            (path.endsWith("/player/telif/index.php") || path.endsWith("/player/youtube.php") ||
                path.endsWith("/youtube.php"))
        ) {
            val candidate = idValue ?: return null
            return youtubeWatchUrlFrom(candidate)
                ?: candidate.takeIf { isValidYoutubeId(it) }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
        }
        return null
    }

    private fun queryParameter(rawQuery: String?, name: String): String? =
        rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.let { raw ->
                var decoded = raw
                // DDizi sometimes URL-encodes the Youtube URL once inside an already encoded query.
                repeat(3) {
                    val next = runCatching {
                        URLDecoder.decode(decoded, Charsets.UTF_8.name())
                    }.getOrNull()
                    if (next != null && next != decoded) decoded = next
                }
                decoded
            }

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
                            val marker = parts.indexOfFirst {
                                it.equals("embed", true) || it.equals("shorts", true) || it.equals("live", true)
                            }
                            if (marker >= 0) parts.getOrNull(marker + 1)?.takeIf { isValidYoutubeId(it) } else null
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

    /**
     * Prefer a broadly supported H.264 stream at or below 720p to reduce buffering
     * on low-bandwidth devices. Fall back to another <=720p format before choosing
     * a higher-resolution H.264 stream.
     */
    fun preferredYoutubeVariantIndex(candidates: List<Pair<String, Int>>): Int? {
        val valid = candidates.withIndex().filter { it.value.second > 0 }
        if (valid.isEmpty()) return candidates.indices.firstOrNull()
        fun isH264(name: String): Boolean =
            name.contains("h264", true) || name.contains("avc", true)

        val h264 = valid.filter { isH264(it.value.first) }
        h264.filter { it.value.second <= 720 }
            .maxByOrNull { it.value.second }
            ?.let { return it.index }

        valid.filter { it.value.second <= 720 }
            .maxByOrNull { it.value.second }
            ?.let { return it.index }

        h264.minByOrNull { it.value.second }
            ?.let { return it.index }

        return valid.minByOrNull { it.value.second }?.index
    }

    /**
     * YouTube's audio links expose codec itags through their query string. Keep one
     * default/original AAC stream where possible, avoiding translated OPUS variants
     * that are often exposed with a missing ("und") or misleading language label.
     */
    fun youtubeAudioTrackPriority(rawUrl: String): Int {
        val query = runCatching { URI(rawUrl).rawQuery.orEmpty() }.getOrDefault("")
        val itag = Regex("""(?:^|&)itag=(\\d+)(?:&|$)""")
            .find(query)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val translated = Regex("""(?:^|&)tlang=[^&]+""")
            .containsMatchIn(query)
        val aacItags = setOf(139, 140, 141, 256, 258)
        return when {
            !translated && itag in aacItags -> 0
            !translated -> 1
            itag in aacItags -> 2
            else -> 3
        }
    }

    fun episodeNumber(value: String): Int? =
        Regex("""(?i)(\d+)\s*\.?\s*b[oö]l[uü]m\b""")
            .find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)\bS\d+\s*E(\d+)\b""").find(value)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)\b\d+\s*[x×]\s*(\d+)\b""").find(value)?.groupValues?.get(1)?.toIntOrNull()

    fun seasonForEpisode(episode: Int?, seasonFinalEpisodes: Collection<Int>): Int? {
        if (episode == null) return null
        return 1 + seasonFinalEpisodes.count { it < episode }
    }

    /**
     * Site-wide episode numbering (e.g. 49.Bölüm) must be mapped to a season-local
     * episode number for CloudStream (S3:E1). User-facing labels keep the original name.
     */
    fun seasonLocalEpisodeNumber(episode: Int?, seasonFinalEpisodes: Collection<Int>): Int? {
        if (episode == null) return null
        val previousSeasonFinal = seasonFinalEpisodes.filter { it < episode }.maxOrNull() ?: return episode
        return episode - previousSeasonFinal
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

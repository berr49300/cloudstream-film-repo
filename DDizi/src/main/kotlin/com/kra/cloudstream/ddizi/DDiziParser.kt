package com.kra.cloudstream.ddizi

import java.net.URI
import java.util.Locale

internal object DDiziParser {
    fun isEpisodeUrl(url: String): Boolean =
        runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
            .startsWith("/izle/", ignoreCase = true)

    fun isSeriesUrl(url: String): Boolean =
        runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
            .startsWith("/diziler/", ignoreCase = true)

    fun cleanTitle(value: String): String = value
        .replace(Regex("""(?i)\s+\d+\s*\.?\s*b[oö]l[uü]m.*$"""), "")
        .replace(Regex("""(?i)\s+(?:son\s+)?b[oö]l[uü]m\s+izle.*$"""), "")
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .trim()

    fun belongsToSeriesEpisode(seriesTitle: String, episodeTitle: String): Boolean {
        val normalizedSeries = normalizeTitle(cleanTitle(seriesTitle))
        val normalizedEpisode = normalizeTitle(episodeTitle)
        return normalizedSeries.isNotBlank() && normalizedEpisode.contains(normalizedSeries)
    }

    fun youtubeWatchUrlIfEmbed(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "ddizi.re" && !host.endsWith(".ddizi.re")) return null
        if (!uri.path.orEmpty().contains("youtube.php", ignoreCase = true)) return null

        val rawId = uri.rawQuery.orEmpty().split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=", "")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?: return null
        if (!Regex("""^[A-Za-z0-9_-]{6,32}$""").matches(rawId)) return null
        "https://www.youtube.com/watch?v=$rawId"
    }.getOrNull()

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ı', 'i')
        .replace('ş', 's')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ö', 'o')
        .replace('ç', 'c')
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

    fun episodeNumber(value: String): Int? =
        Regex("""(?i)(\d+)\s*\.\s*b[oö]l[uü]m""")
            .find(value)?.groupValues?.get(1)?.toIntOrNull()

    fun absolutePath(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
}

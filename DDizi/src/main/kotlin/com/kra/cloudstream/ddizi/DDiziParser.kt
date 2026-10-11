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

    fun youtubeWatchUrlIfEmbed(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host != "ddizi.re" && !host.endsWith(".ddizi.re")) return null
        if (!uri.path.orEmpty().contains("youtube.php", ignoreCase = true)) return null

        val rawId = uri.rawQuery.orEmpty().split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=", "")
            ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() }
            ?: return null
        if (!Regex("""^[A-Za-z0-9_-]{6,32}$""").matches(rawId)) return null
        return "https://www.youtube.com/watch?v=$rawId"
    }

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

    /**
     * DDizi does not expose a separate season field on episode links. Its list does,
     * however, mark season finales explicitly. Use those verified boundaries rather
     * than assigning every episode to season 1 or guessing from release dates.
     */
    fun seasonForEpisode(episode: Int?, seasonFinalEpisodes: Collection<Int>): Int? {
        if (episode == null) return null
        return 1 + seasonFinalEpisodes.count { it < episode }
    }

    fun seasonFinalEpisodeNumbers(titles: Collection<String>): Set<Int> =
        titles.mapNotNull { title ->
            if (!title.contains("sezon final", ignoreCase = true)) null else episodeNumber(title)
        }.toSet()

    fun absolutePath(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
}

package com.kra.cloudstream.dizilla

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object DizillaParser {
    data class EpisodePosition(val season: Int, val episode: Int)

    /**
     * Reads season/episode numbers from a public episode URL or its visible link text.
     * Returns null for a series/season page that does not identify an episode.
     */
    fun cleanSeriesTitle(title: String): String {
        val cleaned = title.replace(
            Regex("""\\s*[-–:|]*\\s*\\d+\\s*\\.?\\s*(?:sezon|season)\\s+\\d+\\s*\\.?\\s*(?:bölüm|bolum|episode).*$""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return cleaned.ifBlank { title.trim() }
    }

    fun parseEpisodePosition(url: String, title: String): EpisodePosition? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        val decoded = runCatching {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        }.getOrDefault(path)
        val source = "$decoded $title".lowercase(Locale.ROOT)

        val seasonFromUrl = Regex(
            """(?:^|[-/ ])(\d+)[- ](?:sezon|season)(?:[-/ ]|$)""",
            RegexOption.IGNORE_CASE
        ).find(source)?.groupValues?.get(1)?.toIntOrNull()
        val episodeFromUrl = Regex(
            """(?:^|[-/ ])(\d+)[- ](?:bolum|bölüm|episode)(?:[-/ ]|$)""",
            RegexOption.IGNORE_CASE
        ).find(source)?.groupValues?.get(1)?.toIntOrNull()

        val seasonFromText = Regex(
            """(\d+)\s*\.?\s*(?:sezon|season)""",
            RegexOption.IGNORE_CASE
        ).find(title)?.groupValues?.get(1)?.toIntOrNull()
        val episodeFromText = Regex(
            """(\d+)\s*\.?\s*(?:bölüm|bolum|episode)""",
            RegexOption.IGNORE_CASE
        ).find(title)?.groupValues?.get(1)?.toIntOrNull()

        val season = seasonFromText ?: seasonFromUrl
        val episode = episodeFromText ?: episodeFromUrl
        if (episode == null) return null

        return EpisodePosition(season ?: 1, episode)
    }
}

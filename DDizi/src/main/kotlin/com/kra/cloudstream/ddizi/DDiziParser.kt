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
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:full\s*)?\d+\s*\.\s*b[oö]l[uü]m.*$"""), "")
        .trim()

    fun episodeNumber(value: String): Int? =
        Regex("""(?i)(\d+)\s*\.\s*b[oö]l[uü]m""")
            .find(value)?.groupValues?.get(1)?.toIntOrNull()

    fun absolutePath(url: String): String =
        runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
}

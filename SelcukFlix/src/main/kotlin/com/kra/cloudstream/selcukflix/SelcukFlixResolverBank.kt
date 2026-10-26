package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class SelcukEpisodeRecord(
    val url: String,
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?
)

/**
 * Small site-data helper. Player resolution is intentionally handled by the normal
 * CloudStream extractor registry plus the one observed Pichive iframe -> source2.php flow.
 */
internal object SelcukFlixResolverBank {
    private const val sitePayloadAesKey = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"
    private const val siteHost = "selcukflix.app"
    private val mapper = jacksonObjectMapper()

    private fun JsonNode.field(name: String): JsonNode? {
        if (!isObject) return null
        val fields = fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            if (entry.key.equals(name, ignoreCase = true)) return entry.value
        }
        return null
    }

    private fun JsonNode.textField(vararg names: String): String? {
        for (name in names) {
            val value = field(name) ?: continue
            if (value.isNull || value.isContainerNode) continue
            val text = value.asText().trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return null
    }

    private fun JsonNode.children(): List<JsonNode> = when {
        isArray -> elements().asSequence().toList()
        isObject -> fields().asSequence().map { it.value }.toList()
        else -> emptyList()
    }

    fun isAccessChallenge(text: String): Boolean =
        Regex(
            """(?is)(cf-chl-|challenge-platform|/cdn-cgi/challenge-platform|verify you are human|just a moment\.\.\.|checking your browser|attention required!\s*\|\s*cloudflare|captcha|access denied|error 1020|error 1015)"""
        ).containsMatchIn(text)

    fun isYouTubeUrl(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("").let { host ->
            host == "youtu.be" || host.endsWith(".youtube.com") || host == "youtube.com" ||
                host.endsWith(".youtube-nocookie.com") || host.endsWith(".googlevideo.com")
        }

    fun normalizeUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().trim('"', '\'')
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("\\\"", "\"")
            .replace(" ", "%20")
        if (value.startsWith("//")) value = "https:$value"
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull() ?: return null
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null
        return uri.toASCIIString()
    }

    fun normalizeSiteUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().trim('"', '\'')
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        value = value.replace(
            Regex("""(?i)^https?://selcukflix\.app(?=(?:dizi|film)/)"""),
            "https://$siteHost/"
        )
        if (value.startsWith("$siteHost/", true)) value = "https://$value"
        val url = normalizeUrl(value, baseUrl) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        return if (host == siteHost || host == "www.$siteHost" ||
            host == "selcukflix.net" || host == "www.selcukflix.net" ||
            host == "selcukflix.com" || host == "www.selcukflix.com"
        ) {
            "https://$siteHost" + (uri.rawPath?.takeIf { it.isNotBlank() } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "") +
                (uri.rawFragment?.let { "#$it" } ?: "")
        } else url
    }

    fun decodeSitePayload(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("{") || value.startsWith("[") || value.startsWith("<")) return value
        if (value.length < 16 || value.length > 1_000_000) return null

        val bytes = runCatching { Base64.getDecoder().decode(value.filterNot(Char::isWhitespace)) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(value.filterNot(Char::isWhitespace)) }.getOrNull()
            ?: return null

        val derivedKey = runCatching {
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            ).take(32).toByteArray(Charsets.UTF_8)
        }.getOrNull()
        val keys = listOfNotNull(
            derivedKey,
            sitePayloadAesKey.toByteArray(Charsets.UTF_8)
        ).distinctBy { String(it, Charsets.UTF_8) }

        for (key in keys) {
            val decoded = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(ByteArray(16))
                )
                String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
            }.getOrNull() ?: continue
            if (isPayload(decoded)) return decoded
        }

        val plain = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrNull() ?: return null
        return plain.takeIf(::isPayload)
    }

    private fun isPayload(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[") ||
            text.contains("source_content", true) ||
            text.contains("<iframe", true) ||
            text.contains("m3u8", true)

    fun extractEmbeddedPayload(html: String): String? {
        if (html.isBlank() || isAccessChallenge(html)) return null
        val document = Jsoup.parse(html)
        val scriptText = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: document.selectFirst("script#__NEXT_DATA__")?.html()
        if (!scriptText.isNullOrBlank()) {
            val root = runCatching { mapper.readTree(scriptText) }.getOrNull()
            val secureData = root?.field("props")?.field("pageProps")?.textField("secureData")
            if (!secureData.isNullOrBlank()) return decodeSitePayload(secureData) ?: secureData
            return scriptText
        }

        val match = Regex(
            """(?is)["']secureData["']\s*:\s*["']([A-Za-z0-9+/_=-]{24,})["']"""
        ).find(html)?.groupValues?.getOrNull(1)
        if (!match.isNullOrBlank()) return decodeSitePayload(match) ?: match
        return html.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
    }

    fun findPosterUrl(root: JsonNode, baseUrl: String): String? {
        val found = mutableListOf<Triple<Int, Int, String>>()
        var order = 0

        fun rank(key: String): Int? {
            val name = key.lowercase().replace('-', '_')
            return when {
                name in setOf("object_poster_url", "poster_url", "posterurl", "poster_path", "posterpath", "poster") -> 0
                name.contains("poster") || name.contains("face") || name.contains("cover") -> 1
                name.contains("thumbnail") || name.contains("thumb") || name.contains("image") -> 2
                name.contains("backdrop") || name.contains("banner") || name.contains("still") -> 3
                else -> null
            }
        }

        fun add(raw: String, priority: Int) {
            val url = normalizeUrl(raw, baseUrl) ?: return
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            if (listOf("placeholder", "no-poster", "no_poster", "no-image", "default-poster", "blank.gif")
                    .any { path.contains(it) }) return
            if (Regex("""(?i)\.(svg|ico)$""").containsMatchIn(path)) return
            found += Triple(priority, order++, url)
        }

        fun walk(node: JsonNode) {
            when {
                node.isObject -> {
                    val fields = node.fields()
                    while (fields.hasNext()) {
                        val entry = fields.next()
                        val priority = rank(entry.key)
                        if (priority != null && entry.value.isTextual) add(entry.value.asText(), priority)
                        if (entry.value.isContainerNode) walk(entry.value)
                    }
                }
                node.isArray -> node.elements().forEachRemaining { walk(it) }
            }
        }
        walk(root)
        return found.minWithOrNull(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })?.third
    }

    fun parseEpisodeRecords(root: JsonNode, baseUrl: String): List<SelcukEpisodeRecord> {
        val page = root.field("content")?.field("result")
            ?: root.field("Content")?.field("Result")
            ?: root
        val related = page.field("RelatedResults") ?: page.field("relatedResults")
            ?: root.field("RelatedResults") ?: root.field("relatedResults")
            ?: return emptyList()
        val seasonResponse = related.field("getSerieSeasonAndEpisodes") ?: return emptyList()
        val seasons = seasonResponse.field("result") ?: seasonResponse.field("seasons") ?: return emptyList()

        val records = mutableListOf<SelcukEpisodeRecord>()
        var episodeFallback = 0
        seasons.children().forEach { season ->
            val seasonNo = season.textField("season_no", "seasonNo", "seasonNumber", "number")?.toIntOrNull()
            val episodes = season.field("episodes") ?: return@forEach
            episodes.children().forEach { episode ->
                val rawSlug = episode.textField("used_slug", "usedSlug", "slug", "url", "link")
                    ?: return@forEach
                val url = normalizeSiteUrl(rawSlug, baseUrl) ?: return@forEach
                episodeFallback++
                val number = episode.textField("episode_no", "episodeNo", "episodeNumber", "number")
                    ?.toIntOrNull() ?: episodeFallback
                val poster = episode.textField("object_poster_url", "poster_url", "posterUrl", "face_url", "image_url")
                    ?.let { normalizeUrl(it, baseUrl) } ?: findPosterUrl(episode, baseUrl)
                records += SelcukEpisodeRecord(
                    url = url,
                    name = episode.textField("episode_text", "episodeText", "epText", "title", "name", "text"),
                    season = seasonNo,
                    episode = number,
                    posterUrl = poster
                )
            }
        }
        return records.sortedWith(
            compareBy<SelcukEpisodeRecord> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        ).distinctBy { it.url }
    }

    fun matchesEpisodeFilter(metadata: String, filter: String): Boolean {
        val value = metadata.lowercase()
        return when (filter.lowercase()) {
            "all", "" -> true
            "dub" -> Regex("""(?i)(dublaj|dubbed|turkish\s*audio|türkçe[\s_-]*ses|\bdub\b)""").containsMatchIn(value)
            "foreign" -> Regex("""(?i)(altyazı|altyazılı|subtitled|original\s*audio|yabancı|\bsub\b)""").containsMatchIn(value)
            "anime" -> Regex("""(?i)(anime|animeler|japon[\s_-]*animasyon|japanese[\s_-]*animation)""").containsMatchIn(value)
            else -> true
        }
    }
}

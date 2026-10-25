package com.kra.cloudstream.selcukflix

import java.net.URI

internal data class SelcukFlixPlayerToken(val token: String, val label: String)
internal data class SelcukFlixResolvedMediaCandidate(val resolverId: Int, val url: String, val label: String)

/**
 * Parses player token and source-file declarations used by SelcukFlix's ContentX/Pichive iframe family.
 * It only reads normal iframe/source API responses; it does not bypass access challenges.
 */
internal object SelcukFlixContentXSourceParser {
    fun canHandle(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.path.orEmpty().endsWith("/iframe.php", ignoreCase = true) &&
            uri.rawQuery.orEmpty().split("&").any {
                java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8").equals("v", ignoreCase = true)
            }
    }.getOrDefault(false)

    fun playerTokens(rawPage: String): List<SelcukFlixPlayerToken> {
        if (rawPage.isBlank() || SelcukFlixResolverBank.isAccessChallenge(rawPage)) return emptyList()
        val normalized = decodePlayerEscapes(rawPage)
        val tokens = linkedMapOf<String, SelcukFlixPlayerToken>()
        val mainToken = Regex("""(?is)(?:window\s*\.\s*)?openPlayer\s*\(\s*['"]([^'"]+)['"]""")
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()
        if (!mainToken.isNullOrBlank()) {
            tokens[mainToken] = SelcukFlixPlayerToken(mainToken, "Altyazılı / Orijinal")
        }

        // Observed player configuration puts the second token beside its "Türkçe Dublaj" label.
        val dubbedPattern = Regex("""(?is),\s*['"]([^'"]{8,})['"]\s*,\s*['"]Türkçe(?:\s+Dublaj)?['"]""")
        dubbedPattern.findAll(normalized).forEach { match ->
            val token = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (token.isNotBlank() && !tokens.containsKey(token)) {
                tokens[token] = SelcukFlixPlayerToken(token, "Türkçe Dublaj")
            }
        }
        return tokens.values.toList()
    }

    fun mediaFiles(rawResponse: String, baseUrl: String): List<String> {
        if (rawResponse.isBlank() || SelcukFlixResolverBank.isAccessChallenge(rawResponse)) return emptyList()
        val normalized = decodePlayerEscapes(rawResponse)
        val payloads = linkedSetOf(normalized)
        val responseValue = Regex("""(?is)["']?response["']?\s*:\s*["']((?:\\.|[^"'])+)["']""")
            .find(normalized)?.groupValues?.getOrNull(1)
        if (!responseValue.isNullOrBlank()) {
            payloads += decodePlayerEscapes(responseValue)
            SelcukFlixResolverBank.decodeSitePayload(responseValue)?.let(payloads::add)
        }
        SelcukFlixResolverBank.decodeSitePayload(normalized)?.let(payloads::add)

        val found = linkedSetOf<String>()
        val filePattern = Regex("""(?is)['"]?file['"]?\s*[:=]\s*['"]((?:\\.|[^'"])+)['"]""")
        payloads.forEach payloadLoop@{ payload ->
            if (SelcukFlixResolverBank.isAccessChallenge(payload)) return@payloadLoop
            filePattern.findAll(decodePlayerEscapes(payload)).forEach matchLoop@{ match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                if (raw.isBlank() || canBeSubtitle(raw)) return@matchLoop
                SelcukFlixResolverBank.normalizeUrl(raw, baseUrl)?.let(found::add)
            }
        }
        return found.toList()
    }

    /**
     * Keeps the API-declared file and the separately testable m.php -> master.m3u8
     * transformation. The transformed URL is a hypothesis until CloudStream playback proves it.
     */
    fun mediaFileCandidates(
        rawUrl: String,
        baseUrl: String,
        rawResolverId: Int = 38,
        derivedResolverId: Int = 39
    ): List<SelcukFlixResolvedMediaCandidate> {
        val url = SelcukFlixResolverBank.normalizeUrl(rawUrl, baseUrl) ?: return emptyList()
        val result = mutableListOf(
            SelcukFlixResolvedMediaCandidate(rawResolverId, url, "raw file")
        )
        val beforeFragment = url.substringBefore("#")
        val fragment = url.substringAfter("#", "").takeIf { "#" in url }?.let { "#$it" }.orEmpty()
        val pathAndQuery = beforeFragment.substringBefore("?")
        val query = beforeFragment.substringAfter("?", "").takeIf { "?" in beforeFragment }?.let { "?$it" }.orEmpty()

        // m.php is the common Pichive endpoint. Some observed player variants expose a
        // differently named PHP wrapper; keep the original URL and add a separately labelled
        // master-playlist hypothesis rather than replacing the only candidate.
        val transformedPath = when {
            Regex("""(?i)/m\.php$""").containsMatchIn(pathAndQuery) ->
                pathAndQuery.replace(Regex("""(?i)/m\.php$"""), "/master.m3u8")
            Regex("""(?i)/[^/]+\.[a-z0-9_-]+\.php$""").containsMatchIn(pathAndQuery) ->
                pathAndQuery.replace(Regex("""(?i)/[^/]+\.[a-z0-9_-]+\.php$"""), "/master.m3u8")
            Regex("""(?i)/[^/]+\.php$""").containsMatchIn(pathAndQuery) ->
                pathAndQuery.replace(Regex("""(?i)/[^/]+\.php$"""), "/master.m3u8")
            else -> null
        }
        if (transformedPath != null) {
            val transformed = transformedPath + query + fragment
            val normalized = SelcukFlixResolverBank.normalizeUrl(transformed, baseUrl)
            if (normalized != null && normalized != url) {
                result += SelcukFlixResolvedMediaCandidate(
                    derivedResolverId,
                    normalized,
                    "PichiveMasterPlaylistResolver • PHP endpoint → master.m3u8 (hypothesis)"
                )
            }
        }
        return result
    }

    fun canBeSubtitle(url: String): Boolean =
        Regex("""(?i)\.(vtt|srt|ass|ssa|ttml)(?:$|[?#])""").containsMatchIn(url)

    private fun decodePlayerEscapes(raw: String): String =
        raw.replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
}

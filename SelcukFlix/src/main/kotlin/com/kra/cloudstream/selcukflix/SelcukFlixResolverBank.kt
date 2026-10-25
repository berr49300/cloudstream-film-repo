package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

internal enum class SelcukMediaKind {
    PLAYER, HLS, DASH, VIDEO, UNKNOWN
}

internal data class SelcukResolverCandidate(
    val id: Int,
    val url: String,
    val kind: SelcukMediaKind,
    val referer: String,
    val note: String? = null
)

internal data class SelcukSubtitleCandidate(
    val language: String,
    val url: String
)

/**
 * Site-isolated resolver test bank.
 *
 * The ID means how a candidate was discovered, not that playback has been proven.
 * Resolver output deliberately preserves multiple IDs for the same URL so the user can
 * compare each discovery path independently in CloudStream.
 */
internal object SelcukFlixResolverBank {
    private val mapper = jacksonObjectMapper()

    private val resolverNames = mapOf(
        1 to "Html5SourceResolver",
        2 to "LazyDataAttributeResolver",
        3 to "SelectedPlayerElementResolver",
        4 to "JsonLdMediaResolver",
        5 to "EmbeddedJsonResolver",
        6 to "HydrationStateResolver",
        7 to "PlayerConfigObjectResolver",
        8 to "StaticScriptUrlResolver",
        9 to "EscapedScriptUrlResolver",
        10 to "JavaScriptVariableResolver",
        11 to "JavaScriptRuntimeResolver",
        12 to "PackedScriptResolver",
        13 to "KnownEncodingResolver",
        14 to "SiteSpecificObfuscationResolver",
        15 to "IframeDiscoveryResolver",
        16 to "NestedIframeResolver",
        17 to "ExtractorRegistryResolver",
        18 to "ObservedRestApiResolver",
        19 to "ObservedAjaxResolver",
        20 to "GraphQlResolver",
        21 to "PlayerIdManifestResolver",
        22 to "RedirectChainResolver",
        23 to "HlsMasterPlaylistResolver",
        24 to "HlsVariantPlaylistResolver",
        25 to "DashManifestResolver",
        26 to "DirectMediaFileResolver",
        27 to "ContainerFormatCandidateResolver",
        28 to "SegmentPlaylistResolver",
        29 to "BrowserNetworkDiscoveryResolver",
        30 to "BlobMediaSourceDiagnostic",
        31 to "ServiceWorkerPlaybackDiagnostic",
        32 to "SignedUrlResolver",
        33 to "HeaderContextResolver",
        34 to "SessionDependentSourceDiagnostic",
        35 to "AlternateAudioAndSubtitleResolver",
        36 to "GenericExtractorFallback",
        37 to "ManualNetworkEvidenceResolver",
        38 to "PichiveSourceApiResolver",
        39 to "PichiveMasterPlaylistResolver"
    )

    private val urlRegex = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+""")
    private val mediaExtensionRegex = Regex(
        """(?i)\.(m3u8|mpd|mp4|m4v|webm|mkv|avi|mov)(?:$|[?#])"""
    )
    private val subtitleExtensionRegex = Regex("""(?i)\.(vtt|srt|ass|ssa)(?:$|[?#])""")
    private val likelyPlayerPath = Regex("""(?i)(embed|player|watch|/e/|/video|iframe|stream|manifest|playlist)""")
    private val base64Regex = Regex("""(?<![A-Za-z0-9+/=_-])([A-Za-z0-9+/_-]{24,}={0,2})(?![A-Za-z0-9+/=_-])""")

    fun resolverName(id: Int): String = resolverNames[id] ?: "UnknownResolver"

    fun resolverCatalog(): List<Pair<Int, String>> =
        resolverNames.entries.sortedBy { it.key }.map { it.key to it.value }

    fun isAccessChallenge(text: String): Boolean =
        Regex(
            """(?is)(cf-chl-|challenge-platform|/cdn-cgi/challenge-platform|verify you are human|just a moment\.\.\.|checking your browser|attention required!\s*\|\s*cloudflare|captcha|access denied|error 1020|error 1015)"""
        ).containsMatchIn(text)

    /**
     * Reads the Next.js page shell used by a previously published SelcukFlix provider.
     * The current .app domain is blocked from this environment, so this is a hypothesis
     * until a real .app response confirms it.
     */
    fun extractEmbeddedPayload(html: String): String? {
        if (html.isBlank() || isAccessChallenge(html)) return null
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: doc.selectFirst("script#__NEXT_DATA__")?.html()
        if (!script.isNullOrBlank()) {
            val root = runCatching { mapper.readTree(script) }.getOrNull()
            val secure = root?.fieldIgnoreCase("props")
                ?.fieldIgnoreCase("pageProps")
                ?.fieldIgnoreCase("secureData")
                ?.takeIf { it.isTextual }
                ?.asText()
            if (!secure.isNullOrBlank()) {
                return decodeSitePayload(secure) ?: secure
            }
            return script
        }

        val secureMatch = Regex(
            """(?is)["']secureData["']\s*:\s*["']([A-Za-z0-9+/_=-]{24,})["']"""
        ).find(html)?.groupValues?.getOrNull(1)
        return secureMatch?.let { decodeSitePayload(it) ?: it }
    }

    /**
     * SelcukFlix app-side public payload encoding observed in independent provider implementations.
     * This decodes the site's ordinary page/API payload; it does not bypass login, CAPTCHA, DRM,
     * Cloudflare challenges, or any other access control. A challenge response must be rejected first.
     */
    fun decodeSitePayload(raw: String): String? =
        decryptSiteEnvelope(raw) ?: decodeBase64Text(raw)

    private fun decryptSiteEnvelope(raw: String): String? {
        val value = raw.trim().filterNot(Char::isWhitespace)
        if (value.length < 24 || value.length > 1_000_000) return null
        val cipherBytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
            ?: return null

        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            val encodedDigest = Base64.getEncoder().encodeToString(digest)
            // The page's client-side payload format uses the first 32 Base64 characters as AES-256 key bytes.
            val keyBytes = encodedDigest.take(32).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ByteArray(16))
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim().takeIf { decoded ->
                decoded.startsWith("{") || decoded.startsWith("[") ||
                    decoded.contains("<iframe", ignoreCase = true) ||
                    decoded.contains("m3u8", ignoreCase = true) ||
                    decoded.contains("source_content", ignoreCase = true)
            }
        }.getOrNull()
    }

    fun decodeBase64Text(raw: String): String? {
        val value = raw.trim()
        if (value.length < 16 || value.length > 250_000) return null
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
            ?: return null
        val decoded = String(bytes, Charsets.UTF_8)
        return decoded.takeIf {
            it.contains("http", ignoreCase = true) ||
                it.contains('{') || it.contains('<') ||
                it.contains("m3u8", ignoreCase = true) ||
                it.contains("source_content", ignoreCase = true)
        }
    }

    fun extractCandidates(
        raw: String,
        baseUrl: String,
        siteUrl: String = baseUrl,
        fromObservedApi: Boolean = false
    ): List<SelcukResolverCandidate> {
        if (raw.isBlank() || isAccessChallenge(raw)) return emptyList()

        val normalized = normalizeEscapes(raw)
        val doc = Jsoup.parse(raw, baseUrl)
        val result = linkedMapOf<String, SelcukResolverCandidate>()

        fun add(
            id: Int,
            rawUrl: String?,
            referrer: String = baseUrl,
            forcePlayerContext: Boolean = false
        ) {
            val url = normalizeUrl(rawUrl, baseUrl) ?: return
            if (isYouTubeUrl(url)) return
            val kind = classify(url, forcePlayerContext)
            if (!forcePlayerContext && kind == SelcukMediaKind.UNKNOWN &&
                !isLikelyPlayerOrMedia(url, siteUrl)
            ) return
            val candidate = SelcukResolverCandidate(id, url, kind, referrer)
            val key = id.toString() + "|" + url + "|" + referrer
            result.putIfAbsent(key, candidate)
        }

        fun addUrlsFromText(id: Int, text: String, referrer: String = baseUrl) {
            urlRegex.findAll(normalizeEscapes(text)).forEach { match ->
                val rawUrl = match.value.trimEnd(')', ']', '}', ',', ';', '.', ':')
                add(id, rawUrl, referrer)
            }
        }

        fun walkJson(node: JsonNode, id: Int, sourceHint: String = "") {
            if (node.isObject) {
                val fields = node.fields()
                while (fields.hasNext()) {
                    val entry = fields.next()
                    val key = entry.key
                    val value = entry.value
                    val keyLower = key.lowercase()
                    if (value.isTextual) {
                        val text = value.asText()
                        val relevantKey = keyLower in setOf(
                            "src", "file", "url", "source", "sources", "embedurl", "contenturl",
                            "videourl", "videofile", "manifest", "playlist", "streamurl",
                            "sourcecontent", "source_content", "rawurl"
                        ) || keyLower.contains("source") ||
                            keyLower.contains("embed") ||
                            keyLower.contains("manifest") ||
                            keyLower.contains("playlist")
                        if (relevantKey) {
                            addUrlsFromText(id, text)
                            if (keyLower in setOf("file", "src", "url", "sources", "streamurl", "videourl")) {
                                addUrlsFromText(7, text)
                            }
                            if (keyLower.contains("sourcecontent") || keyLower.contains("source_content")) {
                                addUrlsFromText(18, text)
                            }
                            if (keyLower.contains("manifest") || keyLower.contains("playlist")) {
                                addUrlsFromText(21, text)
                            }
                        }
                        if (keyLower == "securedata") {
                            val decoded = decodeBase64Text(text)
                            if (!decoded.isNullOrBlank()) {
                                addUrlsFromText(6, decoded)
                                addUrlsFromText(14, decoded)
                            }
                        }
                        if (keyLower.contains("subtitle") || keyLower.contains("caption") ||
                            keyLower == "track" || keyLower == "tracks"
                        ) {
                            addUrlsFromText(35, text)
                        }
                    } else {
                        walkJson(value, id, key)
                    }
                }
            } else if (node.isArray) {
                val elements = node.elements()
                while (elements.hasNext()) walkJson(elements.next(), id, sourceHint)
            }
        }

        // 01 • HTML5 <video>/<source>
        doc.select("video[src], video source[src], source[src]").forEach { element ->
            add(1, element.attr("src"), baseUrl, forcePlayerContext = true)
        }

        // 02 • Lazy data fields, constrained to player/video elements to avoid poster images.
        doc.select(
            "video[data-src], video[data-url], source[data-src], source[data-url], " +
                "iframe[data-src], iframe[data-url], iframe[data-file], " +
                "#player [data-src], #player [data-url], .player [data-src], .player [data-url], " +
                "[data-player][data-src], [data-player][data-url]"
        ).forEach { element ->
            listOf("data-src", "data-url", "data-file", "data-embed").forEach { attr ->
                if (element.hasAttr(attr)) add(2, element.attr(attr), baseUrl, forcePlayerContext = true)
            }
        }

        // 03 • Selected player container
        doc.select("#player, #video, .player, .video-player, [data-player]").forEach { player ->
            player.select("[src], [data-src], [data-url], [data-file]").forEach { element ->
                listOf("src", "data-src", "data-url", "data-file").forEach { attr ->
                    if (element.hasAttr(attr)) add(3, element.attr(attr), baseUrl, forcePlayerContext = true)
                }
            }
        }

        // 04 • JSON-LD media fields
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { mapper.readTree(script.data().ifBlank { script.html() }) }.getOrNull()
            if (json != null) walkJson(json, 4)
        }

        // 05 • Embedded JSON objects
        runCatching { mapper.readTree(normalized) }.getOrNull()?.let { walkJson(it, 5) }
        doc.select("script").forEach { script ->
            val body = script.data().ifBlank { script.html() }
            if (body.contains("http", true) || body.contains("source", true) || body.contains("player", true)) {
                runCatching { mapper.readTree(normalizeEscapes(body)) }.getOrNull()?.let { walkJson(it, 5) }
            }
        }

        // 06 • Next.js/hydration payload; don't execute JavaScript.
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            addUrlsFromText(6, nextData)
            val nextRoot = runCatching { mapper.readTree(nextData) }.getOrNull()
            if (nextRoot != null) walkJson(nextRoot, 6)
            val embedded = extractEmbeddedPayload(raw)
            if (!embedded.isNullOrBlank() && embedded != nextData) {
                addUrlsFromText(6, embedded)
                runCatching { mapper.readTree(embedded) }.getOrNull()?.let { walkJson(it, 6) }
            }
        }

        // 08 • Static URL literals in script text; 09 • escaped URL literals.
        val scripts = doc.select("script").map { it.data().ifBlank { it.html() } }
        scripts.forEach { script ->
            addUrlsFromText(8, script)
            if (script.contains("\\/") || script.contains("\\u0026", true) ||
                script.contains("\\x26", true)
            ) addUrlsFromText(9, script)
        }
        if (normalized != raw) addUrlsFromText(9, normalized)

        // 10 • Explicit JavaScript assignments; never evaluate the script.
        val variableRegex = Regex(
            """(?is)(?:(?:var|let|const)\s+)?[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*["']([^"']*(?:https?:|//)[^"']+)["']"""
        )
        variableRegex.findAll(normalized).forEach { match ->
            addUrlsFromText(10, match.groupValues[1])
        }

        // 12 • Packed-script diagnostic. We inspect visible literals only; no eval/unpacking execution.
        if (Regex("""(?is)eval\s*\(\s*function\s*\(p,a,c,k,e,d\)|}\s*\('.*?'\s*,\s*\d+\s*,\s*\d+\s*,\s*'""")
                .containsMatchIn(normalized)
        ) addUrlsFromText(12, normalized)

        // 13 • Known base64 text containing an explicit URL/JSON marker.
        base64Regex.findAll(normalized).take(24).forEach { match ->
            val decoded = decodeBase64Text(match.groupValues[1]) ?: return@forEach
            if (decoded.contains("http", true) || decoded.contains("m3u8", true) ||
                decoded.contains("source_content", true)
            ) addUrlsFromText(13, decoded)
        }

        // 14 • Previously observed SelcukFlix secureData base64 envelope.
        val secureData = runCatching { mapper.readTree(nextData ?: "") }.getOrNull()
            ?.fieldIgnoreCase("props")
            ?.fieldIgnoreCase("pageProps")
            ?.fieldIgnoreCase("secureData")
            ?.takeIf { it.isTextual }
            ?.asText()
        if (!secureData.isNullOrBlank()) {
            val decoded = decodeBase64Text(secureData)
            if (!decoded.isNullOrBlank()) {
                addUrlsFromText(14, decoded)
                runCatching { mapper.readTree(decoded) }.getOrNull()?.let { walkJson(it, 14) }
            }
        }

        // 15 • Iframe discovery.
        val iframeUrls = linkedSetOf<String>()
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { frame ->
            val rawUrl = frame.attr("src").ifBlank {
                frame.attr("data-src").ifBlank { frame.attr("data-lazy-src") }
            }
            val url = normalizeUrl(rawUrl, baseUrl) ?: return@forEach
            if (!isYouTubeUrl(url)) {
                iframeUrls += url
                add(15, url, baseUrl, forcePlayerContext = true)
            }
        }

        // 17 • Extractor registry candidates are player URLs only, not final media URLs.
        iframeUrls.forEach { add(17, it, baseUrl, forcePlayerContext = true) }

        // 18 • URL supplied by the API field being inspected.
        if (fromObservedApi) {
            add(18, raw, baseUrl, forcePlayerContext = true)
            addUrlsFromText(18, raw)
        }

        // 19 • AJAX/XHR pattern: only produce candidates when an AJAX marker is actually present.
        if (Regex("""(?is)(XMLHttpRequest|\.ajax\s*\(|fetch\s*\(|axios\.(get|post)\s*\()""")
                .containsMatchIn(normalized)
        ) addUrlsFromText(19, normalized)

        // 20 • GraphQL is conditional on an observed marker.
        if (normalized.contains("graphql", true) || normalized.contains("operationName")) {
            addUrlsFromText(20, normalized)
        }

        // 21 • Only actual manifest/playlist fields, never an ID-to-URL template guess.
        val rawTree = runCatching { mapper.readTree(normalized) }.getOrNull()
        if (rawTree != null) walkJson(rawTree, 21, "manifest")

        val observedUrls = urlRegex.findAll(normalized).mapNotNull { match ->
            normalizeUrl(match.value.trimEnd(')', ']', '}', ',', ';', '.', ':'), baseUrl)
        }.distinct().toList()

        // 23-27 • The URL's observed type is separate from the resolver that found it.
        observedUrls.forEach { url ->
            val kind = classify(url, false)
            when (kind) {
                SelcukMediaKind.HLS -> add(23, url, baseUrl, forcePlayerContext = true)
                SelcukMediaKind.DASH -> add(25, url, baseUrl, forcePlayerContext = true)
                SelcukMediaKind.VIDEO -> {
                    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
                    if (path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov")) {
                        add(27, url, baseUrl, forcePlayerContext = true)
                    } else {
                        add(26, url, baseUrl, forcePlayerContext = true)
                    }
                }
                else -> Unit
            }
        }

        // 30 • Blob URLs are never emitted as playback URLs; discover only real HTTP dependencies nearby.
        if (normalized.contains("blob:", true)) {
            addUrlsFromText(30, normalized)
        }

        // 32 • Signed URL diagnostics retain the real URL for playback but never log it.
        observedUrls.filter { hasSignatureLikeQuery(it) }.forEach { add(32, it, baseUrl, forcePlayerContext = true) }

        // 33 • Explicit alternate referer-context candidate for observed iframes.
        iframeUrls.forEach { add(33, it, baseUrl, forcePlayerContext = true) }

        // 36 • Generic extractor fallback, kept separate from the specific iframe/registry path.
        iframeUrls.forEach { add(36, it, baseUrl, forcePlayerContext = true) }
        observedUrls.filter { isLikelyPlayerOrMedia(it, siteUrl) }
            .forEach { add(36, it, baseUrl, forcePlayerContext = true) }

        return result.values.toList()
    }

    fun isPichiveIframe(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        return uri.path.orEmpty().contains("iframe.php", ignoreCase = true) &&
            (host == "pichive.online" || host.endsWith(".pichive.online") ||
                host == "dplayer82.site" || host.endsWith(".dplayer82.site"))
    }

    fun extractPichiveToken(iframeHtml: String): String? {
        if (iframeHtml.isBlank() || isAccessChallenge(iframeHtml)) return null
        return Regex("""(?is)\bopenPlayer\s*\(\s*['"]([A-Za-z0-9_./+=:-]{8,2048})['"]""")
            .find(normalizeEscapes(iframeHtml))
            ?.groupValues?.getOrNull(1)
            ?.takeIf { it.length in 8..2048 }
    }

    fun pichiveSourceApiUrl(iframeUrl: String, token: String): String? {
        if (!isPichiveIframe(iframeUrl) || token.isBlank()) return null
        val uri = runCatching { URI(iframeUrl) }.getOrNull() ?: return null
        val origin = uri.scheme + "://" + uri.rawAuthority
        return origin + "/source2.php?v=" + URLEncoder.encode(token, "UTF-8")
    }

    /**
     * Source2 response parsing is kept as an independent site/player-specific strategy.
     * The m.php -> master.m3u8 relation is a known pattern from public SelcukFlix integrations,
     * but remains HYPOTHESIS on any individual live response until tested.
     */
    fun extractPichiveSourceCandidates(
        responseText: String,
        iframeUrl: String
    ): List<SelcukResolverCandidate> {
        if (responseText.isBlank() || isAccessChallenge(responseText)) return emptyList()
        val root = runCatching { mapper.readTree(responseText) }.getOrNull() ?: return emptyList()
        if (root.get("state")?.asBoolean(false) != true) return emptyList()
        val playlist = root.get("playlist")?.takeIf { it.isArray } ?: return emptyList()
        val result = linkedMapOf<String, SelcukResolverCandidate>()

        playlist.forEach { track ->
            val trackLabel = track.get("title")?.asText().orEmpty().take(50)
            val sources = track.get("sources")?.takeIf { it.isArray } ?: return@forEach
            sources.forEach { source ->
                val rawFile = source.get("file")?.asText().orEmpty()
                if (rawFile.isBlank()) return@forEach
                val label = source.get("label")?.asText().orEmpty().take(24)
                val detail = listOf(trackLabel, label).filter { it.isNotBlank() }.joinToString("/")
                    .ifBlank { "source2" }
                val absolute = normalizeUrl(rawFile, iframeUrl) ?: return@forEach
                if (isYouTubeUrl(absolute)) return@forEach

                val rawCandidate = SelcukResolverCandidate(
                    id = 38,
                    url = absolute,
                    kind = classify(absolute, forcePlayerContext = true),
                    referer = iframeUrl,
                    note = "raw endpoint • $detail"
                )
                result.putIfAbsent("38|$absolute|$detail", rawCandidate)

                val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")
                val masterUrl = if (path.endsWith("m.php", ignoreCase = true)) {
                    val beforeQuery = absolute.substringBefore('#')
                    val query = beforeQuery.substringAfter('?', "").takeIf { '?' in beforeQuery }
                    val basePath = beforeQuery.substringBefore('?')
                        .replace(Regex("""m\.php$""", RegexOption.IGNORE_CASE), "master.m3u8")
                    basePath + (query?.let { "?$it" } ?: "")
                } else {
                    absolute
                }
                val kind = classify(masterUrl, forcePlayerContext = true)
                if (masterUrl != absolute || kind in setOf(SelcukMediaKind.HLS, SelcukMediaKind.DASH, SelcukMediaKind.VIDEO)) {
                    val playlistCandidate = SelcukResolverCandidate(
                        id = 39,
                        url = masterUrl,
                        kind = kind,
                        referer = iframeUrl,
                        note = "derived playlist • $detail"
                    )
                    result.putIfAbsent("39|$masterUrl|$detail", playlistCandidate)
                }
            }
        }
        return result.values.toList()
    }

    fun extractHlsVariants(manifestText: String, manifestUrl: String): List<SelcukResolverCandidate> {
        if (!manifestText.trimStart().startsWith("#EXTM3U", true) ||
            !manifestText.contains("#EXT-X-STREAM-INF", true)
        ) return emptyList()

        val lines = manifestText.lines()
        val result = linkedMapOf<String, SelcukResolverCandidate>()
        lines.forEachIndexed { index, line ->
            if (!line.trim().startsWith("#EXT-X-STREAM-INF", true)) return@forEachIndexed
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                ?: return@forEachIndexed
            val url = normalizeUrl(next.trim(), manifestUrl) ?: return@forEachIndexed
            if (isYouTubeUrl(url)) return@forEachIndexed
            val candidate = SelcukResolverCandidate(
                id = 24,
                url = url,
                kind = SelcukMediaKind.HLS,
                referer = manifestUrl,
                note = "variant"
            )
            result.putIfAbsent(url, candidate)
        }
        return result.values.toList()
    }

    fun extractHlsSubtitles(manifestText: String, manifestUrl: String): List<SelcukSubtitleCandidate> {
        if (!manifestText.trimStart().startsWith("#EXTM3U", true)) return emptyList()
        val result = linkedMapOf<String, SelcukSubtitleCandidate>()
        manifestText.lines().filter {
            it.trim().startsWith("#EXT-X-MEDIA:", true) &&
                it.contains("TYPE=SUBTITLES", true)
        }.forEach { line ->
            val uri = Regex("""(?i)\bURI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: return@forEach
            val language = Regex("""(?i)\bLANGUAGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)\bNAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: "Unknown"
            val url = normalizeUrl(uri, manifestUrl) ?: return@forEach
            result.putIfAbsent(url, SelcukSubtitleCandidate(language, url))
        }
        return result.values.toList()
    }

    fun extractSubtitles(raw: String, baseUrl: String): List<SelcukSubtitleCandidate> {
        if (raw.isBlank() || isAccessChallenge(raw)) return emptyList()
        val doc = Jsoup.parse(raw, baseUrl)
        val result = linkedMapOf<String, SelcukSubtitleCandidate>()
        doc.select("track[src], track[data-src], track[data-url]").forEach { track ->
            val url = normalizeUrl(
                track.attr("src").ifBlank { track.attr("data-src").ifBlank { track.attr("data-url") } },
                baseUrl
            ) ?: return@forEach
            if (isYouTubeUrl(url)) return@forEach
            val kind = track.attr("kind").lowercase()
            if (kind.isBlank() || kind in setOf("subtitles", "captions") || subtitleExtensionRegex.containsMatchIn(url)) {
                val language = track.attr("srclang").ifBlank {
                    track.attr("label").ifBlank { "Unknown" }
                }
                result.putIfAbsent(url, SelcukSubtitleCandidate(language, url))
            }
        }
        subtitleExtensionRegex.findAll(normalizeEscapes(raw)).forEach { _ -> Unit }
        urlRegex.findAll(normalizeEscapes(raw)).forEach { match ->
            val url = normalizeUrl(match.value.trimEnd(')', ']', '}', ',', ';', '.', ':'), baseUrl)
                ?: return@forEach
            if (!subtitleExtensionRegex.containsMatchIn(url) || isYouTubeUrl(url)) return@forEach
            result.putIfAbsent(url, SelcukSubtitleCandidate("Unknown", url))
        }
        return result.values.toList()
    }

    fun isDirectMedia(candidate: SelcukResolverCandidate): Boolean =
        candidate.kind in setOf(SelkucMediaKindCompat.HLS, SelkucMediaKindCompat.DASH, SelkucMediaKindCompat.VIDEO)

    fun classify(url: String, forcePlayerContext: Boolean = false): SelcukMediaKind {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.lowercase())
        return when {
            path.endsWith(".m3u8") -> SelcukMediaKind.HLS
            path.endsWith(".mpd") -> SelcukMediaKind.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
                path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov") -> SelcukMediaKind.VIDEO
            forcePlayerContext -> SelcukMediaKind.PLAYER
            likelyPlayerPath.containsMatchIn(path) -> SelcukMediaKind.PLAYER
            else -> SelcukMediaKind.UNKNOWN
        }
    }

    fun isYouTubeUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    fun normalizeUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val value = normalizeEscapes(raw.trim().trim('"', '\'')).trimEnd(',', ';')
        if (value.startsWith("blob:", true) || value.startsWith("data:", true) ||
            value.startsWith("javascript:", true) || value.startsWith("about:", true)
        ) return null
        val absolute = runCatching {
            when {
                value.startsWith("//") -> "https:" + value
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                else -> URI(baseUrl).resolve(value).toString()
            }
        }.getOrNull() ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return uri.toASCIIString()
    }

    private fun isLikelyPlayerOrMedia(url: String, siteUrl: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        if (mediaExtensionRegex.containsMatchIn(url)) return true
        if (likelyPlayerPath.containsMatchIn(path)) return true
        val siteHost = runCatching { URI(siteUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        return !siteHost.isNullOrBlank() && !host.isNullOrBlank() &&
            !host.equals(siteHost, true) && !isYouTubeUrl(url)
    }

    private fun hasSignatureLikeQuery(url: String): Boolean {
        val query = runCatching { URI(url).rawQuery.orEmpty().lowercase() }.getOrDefault("")
        return listOf("token=", "sig=", "signature=", "expires=", "policy=", "key-pair-id=")
            .any(query::contains)
    }

    private fun normalizeEscapes(value: String): String = value
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("\\u003d", "=", ignoreCase = true)
}

private fun JsonNode.fieldIgnoreCase(name: String): JsonNode? {
    if (!isObject) return null
    val fields = fields()
    while (fields.hasNext()) {
        val entry = fields.next()
        if (entry.key.equals(name, ignoreCase = true)) return entry.value
    }
    return null
}

/*
 * Keep compatibility between media classification and CloudStream link typing in one place.
 * The constants here intentionally mirror SelcukMediaKind without introducing another module.
 */
private object SelkucMediaKindCompat {
    val HLS = SelcukMediaKind.HLS
    val DASH = SelcukMediaKind.DASH
    val VIDEO = SelcukMediaKind.VIDEO
}

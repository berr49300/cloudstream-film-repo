package com.kra.cloudstream.webteizle

import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64

internal enum class WebteIzleCandidateKind { PLAYER, MEDIA, SUBTITLE }

internal enum class WebteIzleProbeStatus {
    CANDIDATE_FOUND,
    SIGNAL_FOUND,
    NOT_FOUND,
    NOT_APPLICABLE,
    NOT_TESTED
}

internal data class WebteIzleResolverCandidate(
    val resolverId: Int,
    val resolverName: String,
    val url: String,
    val kind: WebteIzleCandidateKind,
    val evidence: String,
    val language: String? = null
)

internal data class WebteIzleResolverAttempt(
    val resolverId: Int,
    val resolverName: String,
    val status: WebteIzleProbeStatus,
    val reason: String,
    val candidates: List<WebteIzleResolverCandidate> = emptyList()
)

/**
 * Named, non-deduplicating resolver probe bank.
 *
 * Candidates from different resolver IDs intentionally remain separate so the
 * user can compare them in CloudStream. This is a discovery/diagnostic bank;
 * finding a URL is not evidence that it plays. Unsupported or evidence-dependent
 * paths are explicitly reported rather than silently treated as failures.
 */
internal object WebteIzleResolverBank {
    private data class Definition(val id: Int, val name: String)

    private val definitions = listOf(
        Definition(1, "Html5SourceResolver"),
        Definition(2, "LazyDataAttributeResolver"),
        Definition(3, "SelectedPlayerElementResolver"),
        Definition(4, "JsonLdMediaResolver"),
        Definition(5, "EmbeddedJsonResolver"),
        Definition(6, "HydrationStateResolver"),
        Definition(7, "PlayerConfigObjectResolver"),
        Definition(8, "StaticScriptUrlResolver"),
        Definition(9, "EscapedScriptUrlResolver"),
        Definition(10, "JavaScriptVariableResolver"),
        Definition(11, "JavaScriptRuntimeResolver"),
        Definition(12, "PackedScriptResolver"),
        Definition(13, "KnownEncodingResolver"),
        Definition(14, "SiteSpecificObfuscationResolver"),
        Definition(15, "IframeDiscoveryResolver"),
        Definition(16, "NestedIframeResolver"),
        Definition(17, "ExtractorRegistryResolver"),
        Definition(18, "ObservedRestApiResolver"),
        Definition(19, "ObservedAjaxResolver"),
        Definition(20, "GraphQlResolver"),
        Definition(21, "PlayerIdManifestResolver"),
        Definition(22, "RedirectChainResolver"),
        Definition(23, "HlsMasterPlaylistResolver"),
        Definition(24, "HlsVariantPlaylistResolver"),
        Definition(25, "DashManifestResolver"),
        Definition(26, "DirectMediaFileResolver"),
        Definition(27, "ContainerFormatCandidateResolver"),
        Definition(28, "SegmentPlaylistResolver"),
        Definition(29, "BrowserNetworkDiscoveryResolver"),
        Definition(30, "BlobMediaSourceDiagnostic"),
        Definition(31, "ServiceWorkerPlaybackDiagnostic"),
        Definition(32, "SignedUrlResolver"),
        Definition(33, "HeaderContextResolver"),
        Definition(34, "SessionDependentSourceDiagnostic"),
        Definition(35, "AlternateAudioAndSubtitleResolver"),
        Definition(36, "GenericExtractorFallback"),
        Definition(37, "ManualNetworkEvidenceResolver"),
        Definition(38, "KnownPlayerEmbedConfigResolver")
    )

    private val urlRegex = Regex("""(?i)https?://[^\s"'<>\\]+""")
    private val keyUrlRegex = Regex(
        """(?is)["']?(?:file|src|url|source|contentUrl|embedUrl|hls|manifest)["']?\s*:\s*["']([^"']+)["']"""
    )
    private val variableUrlRegex = Regex(
        """(?is)\b(?:var|let|const)\s+[A-Za-z_$][\w$]*\s*=\s*["'](https?://[^"']+)["']"""
    )
    private val packerRegex = Regex(
        """(?is)eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d"""
    )
    private val obfuscationRegex = Regex(
        """(?i)(?:\batob\s*\(|\bfromCharCode\b|\bdc_hello\b|\bdc_[a-z0-9_]+\s*\(|\b_0x[0-9a-f]+\b)"""
    )
    // Function-call shape observed in public WebteIzle provider references. The resulting
    // embed URL templates are hypotheses until confirmed against current site payloads.
    private val knownPlayerConfigRegex = Regex(
        """(?i)\b(vidmoly|filemoon|okru)\s*\(\s*['"]([A-Za-z0-9_-]{3,256})['"]\s*,\s*['"]"""
    )

    private val youtubeHostRoots = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "youtube.googleapis.com", "youtubei.googleapis.com", "googlevideo.com"
    )

    private val knownPlayerFamilies = linkedMapOf(
        "vidmoly.to" to "VidMoly",
        "vidmoly.me" to "VidMoly",
        "vidmoly.net" to "VidMoly",
        "vidmoly.sx" to "VidMoly",
        "filemoon.to" to "Filemoon",
        "filemoon.sx" to "Filemoon",
        "filemoon.in" to "Filemoon",
        "filemoon.nl" to "Filemoon",
        "pixeldrain.com" to "PixelDrain",
        "pixeldra.in" to "PixelDrain",
        "ok.ru" to "OK.ru",
        "odnoklassniki.ru" to "OK.ru"
    )

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    private fun isWithinHostRoot(host: String, root: String): Boolean =
        host == root || host.endsWith(".$root")

    /** True for YouTube video/CDN URLs, which this provider must not advertise as film sources. */
    internal fun isYouTubeUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return youtubeHostRoots.any { isWithinHostRoot(host, it) }
    }

    /** Returns a known third-party player family without accepting lookalike domains. */
    internal fun playerFamily(url: String): String? {
        val host = hostOf(url) ?: return null
        return knownPlayerFamilies.entries.firstOrNull { (root, _) ->
            isWithinHostRoot(host, root)
        }?.value
    }

    /** Detects visible access challenges; detection is diagnostic, never a bypass. */
    internal fun isAccessChallenge(html: String): Boolean =
        Regex(
            """(?is)(cf-chl-|challenge-platform|/cdn-cgi/challenge-platform|verify you are human|just a moment\.\.\.|checking your browser|attention required!\s*\|\s*cloudflare|captcha)"""
        ).containsMatchIn(html)

    fun inspect(
        html: String,
        baseUrl: String,
        observedAjaxResponse: Boolean = false,
        manualNetworkEvidence: List<String> = emptyList(),
        nestedIframeUrls: List<String>? = null
    ): List<WebteIzleResolverAttempt> {
        val document = Jsoup.parse(html, baseUrl)
        val scripts = document.select("script").map { it.data().ifBlank { it.html() } }
        val normalizedHtml = normalizeEscapes(html)
        val candidates = mutableMapOf<Int, MutableList<WebteIzleResolverCandidate>>()
        val signals = mutableMapOf<Int, String>()
        val explicitStatuses = mutableMapOf<Int, Pair<WebteIzleProbeStatus, String>>()

        fun signal(id: Int, reason: String) {
            signals.putIfAbsent(id, reason)
        }

        fun add(
            id: Int,
            rawUrl: String?,
            evidence: String,
            kindOverride: WebteIzleCandidateKind? = null,
            language: String? = null
        ) {
            val url = normalizeUrl(rawUrl, baseUrl) ?: return
            if (isYouTubeUrl(url)) {
                signal(
                    id,
                    "Filtered YouTube URL: trailer/behind-the-scenes links are not accepted as WebteIzle publication sources."
                )
                return
            }
            if (isStaticAsset(url) && kindOverride != WebteIzleCandidateKind.SUBTITLE) return
            if (url.startsWith("blob:", ignoreCase = true)) {
                signal(30, "blob: URL observed; it is not treated as a portable media URL.")
                return
            }
            if (!isPlausibleCandidate(url, kindOverride, evidence)) return
            val definition = definitions.firstOrNull { it.id == id } ?: return
            val item = WebteIzleResolverCandidate(
                resolverId = id,
                resolverName = definition.name,
                url = url,
                kind = kindOverride ?: classify(url),
                evidence = evidence,
                language = language
            )
            val list = candidates.getOrPut(id) { mutableListOf() }
            if (list.none { it.url == item.url && it.kind == item.kind && it.language == item.language }) {
                list += item
            }
        }

        // 01 — actual HTML media elements and their nested <source> nodes.
        document.select("video[src], video source[src], audio[src], audio source[src], source[src]")
            .forEach { add(1, it.attr("src"), "<${it.tagName()}> src attribute") }

        // 02 — lazy data fields, excluding image posters and ordinary page assets.
        document.select("[data-src], [data-url], [data-file]").forEach { element ->
            if (element.tagName() in setOf("img", "picture", "source") &&
                element.attr("src").matches(Regex("(?i).*\\.(?:jpg|jpeg|png|gif|webp|avif)(?:[?#].*)?$"))) {
                return@forEach
            }
            listOf("data-src", "data-url", "data-file").forEach { attr ->
                if (element.hasAttr(attr)) add(2, element.attr(attr), "${element.tagName()}[$attr]")
            }
        }

        // 03 — explicit player containers, with no assumption that every site uses the same ID.
        document.select("#player, #video, #diziyouSource, [id*=player], [id*=video]")
            .forEach { element ->
                listOf("src", "data-src", "data-url", "data-file").forEach { attr ->
                    if (element.hasAttr(attr)) add(3, element.attr(attr), "observed player-like element #${element.id()} attr=$attr")
                }
            }

        // 04 — only media-related JSON-LD fields; a page's canonical "url" is not enough.
        document.select("script[type=application/ld+json]").forEach { script ->
            val body = script.data().ifBlank { script.html() }
            Regex("""(?is)["'](?:contentUrl|embedUrl)["']\s*:\s*["']([^"']+)["']""")
                .findAll(body).forEach { add(4, it.groupValues[1], "JSON-LD media field") }
            Regex("""(?is)["']url["']\s*:\s*["'](https?://[^"']+)["']""")
                .findAll(body).forEach {
                    val value = it.groupValues[1]
                    if (looksLikeMediaOrPlayer(value)) add(4, value, "JSON-LD url field with media/player shape")
                }
        }

        // 05 — embedded JSON/config blocks.
        document.select("script[type=application/json], script[type*=json]").forEach { script ->
            keyUrlRegex.findAll(script.data().ifBlank { script.html() }).forEach {
                add(5, it.groupValues[1], "embedded JSON media key")
            }
        }
        scripts.filter { it.contains("sources", true) || it.contains("contentUrl", true) }
            .forEach { body ->
                keyUrlRegex.findAll(body).forEach {
                    add(5, it.groupValues[1], "inline embedded JSON-like media key")
                }
            }

        // 06 — hydration state is scanned only when its marker is really present.
        scripts.filter {
            it.contains("__NEXT_DATA__") || it.contains("__NUXT__") ||
                it.contains("__INITIAL_STATE__") || it.contains("__INITIAL_DATA__")
        }.forEach { body ->
            signal(6, "hydration/initial-state marker found; URLs below are candidates only.")
            urlRegex.findAll(normalizeEscapes(body)).forEach {
                add(6, it.value, "URL literal inside hydration state")
            }
        }

        // 07 — player-config objects with a file/src/url/source key.
        keyUrlRegex.findAll(normalizedHtml).forEach {
            add(7, it.groupValues[1], "player configuration key/value")
        }

        // 08 — static URL literals in script text; only player/media-looking URLs qualify.
        scripts.forEach { body ->
            urlRegex.findAll(normalizeEscapes(body)).forEach {
                val value = it.value.trimEnd(',', ';', ')', ']', '}')
                if (looksLikeMediaOrPlayer(value)) add(8, value, "static script URL literal")
            }
        }

        // 09 — escaped URL literals after narrowly normalizing common JS escapes.
        val escaped = normalizeEscapes(html)
        if (escaped != html) {
            urlRegex.findAll(escaped).forEach {
                val value = it.value.trimEnd(',', ';', ')', ']', '}')
                if (looksLikeMediaOrPlayer(value)) add(9, value, "escaped script URL normalized")
            }
        }

        // 10 — URL-valued JavaScript variables, kept separate from broad static scans.
        variableUrlRegex.findAll(escaped).forEach {
            add(10, it.groupValues[1], "URL assigned to a JavaScript variable")
        }

        // 11 — no JavaScript runtime is executed by this static probe.
        explicitStatuses[11] = WebteIzleProbeStatus.NOT_APPLICABLE to
            "Static inspection only; no JavaScript runtime is configured or executed."

        // 12 — detect packed code, but do not claim it was unpacked.
        if (scripts.any { packerRegex.containsMatchIn(it) }) {
            signal(12, "P.A.C.K.E.R.-style signature detected; unpacking is not confirmed by this probe.")
        }

        // 13 — decode only values explicitly passed to atob(), and only emit URL-shaped results.
        Regex("""(?is)\batob\s*\(\s*["']([A-Za-z0-9+/=]{12,})["']\s*\)""")
            .findAll(html).forEach { match ->
                val decoded = runCatching {
                    String(Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                urlRegex.findAll(normalizeEscapes(decoded)).forEach {
                    add(13, it.value, "URL recovered from an observed atob() argument")
                }
            }

        // 14 — record obfuscation signatures; no guessed decoding or domain synthesis.
        if (scripts.any { obfuscationRegex.containsMatchIn(it) }) {
            signal(14, "Obfuscation marker detected; no site-specific transform is proven.")
        }

        // 38 — recover the player family and content ID from an observed legacy player function.
        // These templates come from public CloudStream provider references, not from bypassing
        // Cloudflare or from arbitrary ID-to-URL guessing. Both observed VidMoly host variants
        // are retained as separate test candidates because old references disagree on the host.
        knownPlayerConfigRegex.findAll(html).forEach { match ->
                val family = match.groupValues[1].lowercase()
                val playerId = match.groupValues[2]
                when (family) {
                    "vidmoly" -> {
                        add(
                            38,
                            "https://vidmoly.net/embed-$playerId.html",
                            "observed vidmoly(id, ...) call; .net template hypothesis from public provider reference",
                            WebteIzleCandidateKind.PLAYER
                        )
                        add(
                            38,
                            "https://vidmoly.to/embed-$playerId.html",
                            "observed vidmoly(id, ...) call; .to template hypothesis from another public provider reference",
                            WebteIzleCandidateKind.PLAYER
                        )
                    }
                    "filemoon" -> add(
                        38,
                        "https://filemoon.sx/e/$playerId",
                        "observed filemoon(id, ...) call; /e/id template hypothesis from public provider reference",
                        WebteIzleCandidateKind.PLAYER
                    )
                    "okru" -> add(
                        38,
                        "https://odnoklassniki.ru/videoembed/$playerId",
                        "observed okru(id, ...) call; videoembed template hypothesis from public provider reference",
                        WebteIzleCandidateKind.PLAYER
                    )
                }
        }

        // 15 — direct iframe discovery.
        document.select("iframe[src], iframe[data-src]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("data-src") }
            add(15, value, "iframe src/data-src", WebteIzleCandidateKind.PLAYER)
        }

        // 16 — these URLs may only be supplied after a parent iframe was fetched separately.
        if (nestedIframeUrls == null) {
            explicitStatuses[16] = WebteIzleProbeStatus.NOT_TESTED to
                "A child iframe must be fetched separately with bounded depth; the current document alone cannot prove its chain."
        } else {
            nestedIframeUrls.forEach {
                add(16, it, "iframe discovered in a separately fetched child-player document", WebteIzleCandidateKind.PLAYER)
            }
            if (nestedIframeUrls.isEmpty()) {
                explicitStatuses[16] = WebteIzleProbeStatus.NOT_FOUND to
                    "One child-player document was fetched; it contained no nested iframe URL."
            }
        }

        // 17 — an iframe/player candidate is routed through CloudStream's extractor registry.
        val playerUrls = candidates.values.flatten()
            .filter { it.kind == WebteIzleCandidateKind.PLAYER }
            .map { it.url }.distinct()
        playerUrls.forEach { add(17, it, "player candidate handed to loadExtractor", WebteIzleCandidateKind.PLAYER) }

        // 18/19/20 — endpoint shape is evidence for investigation, not a playable media URL.
        if (scripts.any { Regex("""(?i)(/api/|axios\.|fetch\s*\()""").containsMatchIn(it) }) {
            signal(18, "REST-like/API call marker found; live request/response schema not inferred here.")
        }
        if (observedAjaxResponse || scripts.any {
                Regex("""(?i)(XMLHttpRequest|/ajax/|\.asp(?:\?|['"]|$))""").containsMatchIn(it)
            }) {
            signal(19, if (observedAjaxResponse) {
                "Provider received a response from an AJAX endpoint; its payload was processed separately."
            } else {
                "AJAX/XHR endpoint marker found; response semantics still need validation."
            })
        }
        if (Regex("""(?i)(graphql|/gql(?:/|['"?])|application/graphql)""").containsMatchIn(html)) {
            signal(20, "GraphQL marker found; no query or response is assumed.")
        }

        // 21 — no manifest URL is synthesized from IDs without an observed mapping template.
        explicitStatuses[21] = WebteIzleProbeStatus.NOT_APPLICABLE to
            "No verified player-ID-to-manifest template; URL synthesis is deliberately disabled."

        // 22 — static source text cannot validate redirect status/location headers.
        explicitStatuses[22] = WebteIzleProbeStatus.NOT_TESTED to
            "HTTP redirect metadata is required; a source URL alone is not a redirect-chain test."

        // 23/25/26/27 — media types are independent candidates, not discovery strategies.
        val firstPass = candidates.values.flatten().distinctBy { "${it.resolverId}|${it.url}|${it.kind}" }
        firstPass.forEach { candidate ->
            when {
                extension(candidate.url) == "m3u8" ->
                    add(23, candidate.url, "M3U8 candidate found by upstream resolver", WebteIzleCandidateKind.MEDIA)
                extension(candidate.url) == "mpd" ->
                    add(25, candidate.url, "MPD candidate found by upstream resolver", WebteIzleCandidateKind.MEDIA)
                extension(candidate.url) in setOf("mp4", "m4v", "webm") ->
                    add(26, candidate.url, "direct media-file candidate found by upstream resolver", WebteIzleCandidateKind.MEDIA)
                extension(candidate.url) in setOf("mkv", "avi", "mov") ->
                    add(27, candidate.url, "container extension candidate found by upstream resolver", WebteIzleCandidateKind.MEDIA)
            }
        }

        // 24 — inspect a manifest body only if a manifest was actually supplied as the input.
        if (html.contains("#EXT-X-STREAM-INF")) {
            val lines = html.lines()
            lines.forEachIndexed { index, line ->
                if (line.contains("#EXT-X-STREAM-INF") && index + 1 < lines.size) {
                    add(24, lines[index + 1].trim(), "variant playlist referenced by HLS master", WebteIzleCandidateKind.MEDIA)
                }
            }
            signal(24, "HLS master playlist marker found; variants are listed as distinct candidates.")
        }

        // 28 — segments are diagnostic signals, not standalone full-video links.
        if (html.contains("#EXTM3U") && Regex("""(?i)(\.ts|\.m4s)(?:[?#]|$)""").containsMatchIn(html)) {
            signal(28, "Playlist segment references found; individual TS/M4S segments are not emitted as full videos.")
        }

        explicitStatuses[29] = WebteIzleProbeStatus.NOT_TESTED to
            "Requires user/browser developer-tools network evidence; none was supplied to this static scan."

        if (Regex("""(?i)blob:[^"'\s<>]+""").containsMatchIn(html)) {
            signal(30, "blob: URL marker found; trace the underlying MediaSource/network stream instead.")
        }
        if (Regex("""(?i)(navigator\.serviceWorker|serviceWorker\.register)""").containsMatchIn(html)) {
            signal(31, "Service Worker marker found; CloudStream is not assumed to provide browser SW context.")
        }

        // 32 — signed URLs remain in memory for playback but never enter candidate labels or logs.
        val signedParam = Regex("""(?i)[?&](?:token|sig|signature|expires|exp|auth_key|policy)=([^&#]+)""")
        candidates.values.flatten().filter { signedParam.containsMatchIn(it.url) }.toList()
            .forEach { add(32, it.url, "signed/expiring URL candidate (query values are not logged)", it.kind) }

        // 33 — do not invent header variations; use only the known page as the referer baseline.
        explicitStatuses[33] = WebteIzleProbeStatus.NOT_TESTED to
            "Referer is passed from the observed parent page; alternative headers are not guessed."

        if (isAccessChallenge(html)) {
            signal(34, "Access/session challenge marker detected; no access control is bypassed.")
        } else {
            explicitStatuses[34] = WebteIzleProbeStatus.NOT_TESTED to
                "Static page text cannot rule session dependence in or out without status/header comparisons."
        }

        // 35 — HTML tracks and HLS audio/subtitle declarations.
        document.select("track[src], [data-subtitle], [data-caption]").forEach { element ->
            val raw = element.attr("src").ifBlank {
                element.attr("data-subtitle").ifBlank { element.attr("data-caption") }
            }
            val lang = element.attr("srclang").ifBlank {
                element.attr("label").ifBlank { "Altyazı" }
            }
            add(35, raw, "subtitle/track attribute", WebteIzleCandidateKind.SUBTITLE, lang)
        }
        Regex("""(?is)#EXT-X-MEDIA:[^\r\n]*(?:TYPE=SUBTITLES|TYPE=AUDIO)[^\r\n]*URI=["']([^"']+)["']""")
            .findAll(html).forEach {
                val line = it.value
                val lang = Regex("""(?i)LANGUAGE=["']([^"']+)["']""").find(line)?.groupValues?.get(1)
                    ?: if (line.contains("SUBTITLES")) "Altyazı" else "Ses"
                add(35, it.groupValues[1], "HLS audio/subtitle group URI", WebteIzleCandidateKind.SUBTITLE, lang)
            }

        // 36 — generic extractor fallback is intentionally separate from the registry path.
        playerUrls.forEach { add(36, it, "generic extractor fallback for a player candidate", WebteIzleCandidateKind.PLAYER) }

        if (manualNetworkEvidence.isNotEmpty()) {
            manualNetworkEvidence.forEach { add(37, it, "user-provided manual network evidence") }
        } else {
            explicitStatuses[37] = WebteIzleProbeStatus.NOT_TESTED to
                "No manual browser/CloudStream network evidence has been supplied yet."
        }

        return definitions.map { definition ->
            val found = candidates[definition.id].orEmpty().toList()
            val explicit = explicitStatuses[definition.id]
            val signal = signals[definition.id]
            val status = when {
                found.isNotEmpty() -> WebteIzleProbeStatus.CANDIDATE_FOUND
                signal != null -> WebteIzleProbeStatus.SIGNAL_FOUND
                explicit != null -> explicit.first
                else -> WebteIzleProbeStatus.NOT_FOUND
            }
            val reason = when {
                found.isNotEmpty() -> "${found.size} candidate(s); playback remains unverified."
                signal != null -> signal
                explicit != null -> explicit.second
                else -> "No matching signal in the supplied HTML/input; this is not proof the method never works."
            }
            WebteIzleResolverAttempt(definition.id, definition.name, status, reason, found)
        }
    }


    /** Extract iframe URLs from one explicitly fetched child page (caller enforces depth/cycle limits). */
    fun findIframeUrls(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("iframe[src], iframe[data-src]")
            .mapNotNull { element ->
                val raw = element.attr("src").ifBlank { element.attr("data-src") }
                normalizeUrl(raw, baseUrl)
            }
            .distinct()
    }

    private fun normalizeUrl(raw: String?, baseUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val value = normalizeEscapes(raw.trim().trim('"', '\'', '`')).trimEnd(';', ',')
        if (value.startsWith("blob:", true) || value.startsWith("data:", true)) return null
        return runCatching {
            val resolved = if (value.startsWith("//")) "https:$value"
                else if (value.startsWith("http://", true) || value.startsWith("https://", true)) value
                else URI(baseUrl).resolve(value).toString()
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun normalizeEscapes(value: String): String = value
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    private fun extension(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()

    private fun classify(url: String): WebteIzleCandidateKind =
        if (extension(url) in setOf("m3u8", "mpd", "mp4", "m4v", "webm", "mkv", "avi", "mov", "ts", "m4s")) {
            WebteIzleCandidateKind.MEDIA
        } else if (extension(url) in setOf("vtt", "srt", "ass", "ssa")) {
            WebteIzleCandidateKind.SUBTITLE
        } else {
            WebteIzleCandidateKind.PLAYER
        }

    private fun isStaticAsset(url: String): Boolean =
        extension(url) in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "css", "js", "woff", "woff2", "ttf", "ico")

    private fun isPlausibleCandidate(
        url: String,
        kindOverride: WebteIzleCandidateKind?,
        evidence: String
    ): Boolean {
        if (kindOverride == WebteIzleCandidateKind.SUBTITLE) return true
        if (playerFamily(url) != null) return true
        if (looksLikeMediaOrPlayer(url)) return true
        return evidence.contains("iframe", true) || evidence.contains("player", true)
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ext = extension(url)
        if (ext in setOf("m3u8", "mpd", "mp4", "m4v", "webm", "mkv", "avi", "mov", "ts", "m4s", "vtt", "srt", "ass", "ssa")) {
            return true
        }
        return playerFamily(url) != null ||
            listOf("/embed", "/player", "/video", "/stream", "/watch", "/e/")
                .any { it in path }
    }
}

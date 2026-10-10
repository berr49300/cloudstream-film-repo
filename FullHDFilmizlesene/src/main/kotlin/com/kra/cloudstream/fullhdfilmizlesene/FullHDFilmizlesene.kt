package com.kra.cloudstream.fullhdfilmizlesene

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Independent provider: no dependency on Dizilla code. */
class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullHDFilmizlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override var sequentialMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenen Filmler",
        "$mainUrl/filmizle/aile-filmleri" to "Aile Filmleri",
        "$mainUrl/filmizle/aksiyon-filmleri" to "Aksiyon Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, referer = "$mainUrl/").document
        // The live catalogue uses li.film cards; the fallback is intentionally
        // limited to catalogue containers so navigation/category links aren't movies.
        val cards = doc.select("li.film, li.movie, article.film, article.movie, .film-item, .movie-item, .film-card, .movie-card, .poster-item")
        val scoped = if (cards.isNotEmpty()) cards else
            doc.select(".films li, .movies li, .film-list li, .movie-list li, .film-list article, .movie-list article, main article, #content article")
        val items = scoped.mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!isMovieUrl(href)) return@mapNotNull null
            card.toSearchResult(href)
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val response = app.get("$mainUrl/autocomplete/q.php?q=" + query.urlEncoded(),
            referer = "$mainUrl/", headers = mapOf("Accept" to "application/json, text/plain, */*"))
        val results = mutableListOf<SearchResponse>()
        runCatching { collectJsonResults(mapper.readTree(response.text), results) }
        if (results.isEmpty()) response.document.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (isMovieUrl(href)) a.toSearchResult(href)?.let(results::add)
        }
        if (results.isEmpty()) app.get("$mainUrl/?s=" + query.urlEncoded(), referer = "$mainUrl/")
            .document.select("a[href]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (isMovieUrl(href)) a.toSearchResult(href)?.let(results::add)
            }
        return results.distinctBy { it.url }.take(30)
    }

    private fun collectJsonResults(n: JsonNode, out: MutableList<SearchResponse>) {
        when {
            n.isArray -> n.forEach { collectJsonResults(it, out) }
            n.isObject -> {
                val url = textField(n, "url", "link", "permalink", "href")
                val title = textField(n, "title", "name", "label", "text")
                val poster = textField(n, "poster", "image", "thumbnail", "img")
                if (!url.isNullOrBlank() && !title.isNullOrBlank()) {
                    val absolute = fixUrlNull(url)
                    if (absolute != null && isMovieUrl(absolute))
                        out.add(newMovieSearchResponse(title.decodeHtml(), absolute, TvType.Movie) {
                            posterUrl = poster?.let { fixUrlNull(it) }
                        })
                }
                n.fields().forEachRemaining { (_, child) -> if (child.isContainerNode) collectJsonResults(child, out) }
            }
        }
    }

    private fun textField(n: JsonNode, vararg keys: String): String? =
        keys.asSequence().mapNotNull { n.get(it)?.takeIf { v -> v.isValueNode }?.asText() }
            .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val d = app.get(url, referer = "$mainUrl/").document
        val title = d.selectFirst("h1")?.text()?.trim()
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: d.title().substringBefore("|").substringBefore(" - ").trim()
        if (title.isBlank()) return null
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: d.selectFirst("img")?.let { imageUrl(it) }
        val plot = d.selectFirst("meta[name=description]")?.attr("content")
            ?: d.selectFirst("[itemprop=description], .description, .film-aciklama")?.text()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(title + " " + d.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())?.value?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot?.trim()
            this.year = year
            this.tags = d.select("a[rel=tag], .genre a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data, referer = "$mainUrl/")
        val d = response.document
        // This provider should expose Turkish subtitles only.
        val turkishSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            if (isTurkishSubtitleLabel(subtitle.lang)) subtitleCallback(subtitle)
        }
        val html = response.text
        // Search the movie page itself too; some pages publish subtitles outside the player iframe.
        runCatching { emitTurkishSubtitles(html, data, turkishSubtitleCallback) }
        val urls = linkedSetOf<String>()

        // Collect every URL-bearing player surface, not only known host names.
        d.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src], a[href]")
            .forEach { e ->
                listOf("src", "data-src", "data-url", "href").forEach { attr ->
                    val raw = e.attr(attr).trim()
                    if (raw.isNotBlank()) fixUrlNull(raw)?.takeIf { isCandidateEmbed(it) }?.let(urls::add)
                }
            }
        // Decode all scx values and scan inline scripts for direct/escaped media URLs.
        parseScx(html).forEach { value ->
            val decoded = decodeAny(value)
            (listOfNotNull(decoded) + Regex("""https?:\\?/\\?/[^"'\\s<>\\]+""")
                .findAll(value).map { it.value.replace("\\/", "/") }.toList())
                .forEach { raw -> fixUrlNull(raw)?.takeIf { isCandidateEmbed(it) }?.let(urls::add) }
        }
        Regex("""(?i)(?:https?:)?\\?/\\?/[^"'\\s<>\\]+""").findAll(html).forEach { m ->
            val raw = m.value.replace("\\\\/", "/").let { if (it.startsWith("//")) "https:$it" else it }
            fixUrlNull(raw)?.takeIf { isCandidateEmbed(it) }?.let(urls::add)
        }

        var emitted = false
        urls.forEach { link ->
            val uri = runCatching { URI(link) }.getOrNull() ?: return@forEach
            val host = uri.host.orEmpty().lowercase()
            val path = uri.path.orEmpty().lowercase()
            val origin = "${uri.scheme}://${uri.host}/"

            // TurboImgz is a distinct player: its page contains a plain HLS
            // "file" field and is not handled by the generic RapidVid decoder.
            if (host == "turbo.imgz.me" || host.endsWith(".turbo.imgz.me")) {
                val resolved = runCatching { resolveTurboImgz(link, turkishSubtitleCallback) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    callback.invoke(newExtractorLink("FullHDFilmizlesene • TurboImgz", "TurboImgz", resolved, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    })
                    emitted = true
                }
                return@forEach
            }

            // VidMoxy's current player is often double-packed and stores the
            // HLS URL as hex escapes. The generic CloudStream extractor cannot
            // reliably decode this player, so resolve it explicitly.
            if (host.contains("vidmoxy")) {
                val resolved = runCatching { resolveVidMoxy(link, turkishSubtitleCallback) }.getOrNull()
                if (!resolved.isNullOrBlank() && (resolved.startsWith("https://") || resolved.startsWith("http://"))) {
                    // RapidVid/imgz HLS masters often have extensionless CDN paths (/m9/…),
                    // so checking for .m3u8 incorrectly drops valid playable streams.
                    val embedOrigin = "${uri.scheme}://${uri.host}/"
                    callback.invoke(newExtractorLink("FullHDFilmizlesene • VidMoxy", "VidMoxy", resolved, ExtractorLinkType.M3U8) {
                        this.referer = embedOrigin
                        this.headers = mapOf("Referer" to embedOrigin)
                    })
                    emitted = true
                    return@forEach
                }
            }

            // Resolve known RapidVid player pages before considering a URL to be media.
            if (host.contains("rapidvid") || (host.contains("imgz.me") && !host.contains("turbo.imgz.me"))) {
                val resolved = runCatching { resolveRapidVid(link, turkishSubtitleCallback) }.getOrNull()
                if (!resolved.isNullOrBlank() && (resolved.startsWith("https://") || resolved.startsWith("http://"))) {
                    // imgz HLS masters may not end in .m3u8; the reference scraper confirms these are HLS.
                    callback.invoke(newExtractorLink("FullHDFilmizlesene • RapidVid", "RapidVid", resolved, ExtractorLinkType.M3U8) {
                        this.referer = origin
                        this.headers = mapOf("Referer" to origin)
                    })
                    emitted = true
                    return@forEach
                }
            }

            // A URL containing ".mp4" in its query is not necessarily a video.
            // Only emit direct media when the URL path itself has a media extension.
            if (isDirectMediaUrl(link)) {
                val type = when {
                    path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                    path.endsWith(".mpd") -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback.invoke(newExtractorLink(name, name, link, type) {
                    this.referer = data
                    this.headers = mapOf("Referer" to data)
                })
                emitted = true
            } else if (host != URI(mainUrl).host && !host.endsWith("fullhdfilmizlesene.now")) {
                runCatching { loadExtractor(link, data, turkishSubtitleCallback) { callback(it); emitted = true } }
            }
        }
        return emitted
    }

    private fun baseN(value: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value == 0) return "0"
        var n = value
        var out = ""
        while (n > 0) {
            out = digits[n % radix] + out
            n /= radix
        }
        return out
    }

    /** Decode the Dean Edwards P.A.C.K.E.R wrapper used by VidMoxy. */
    private fun unpackPacker(packed: String): String? = runCatching {
        val m = Regex("""\}\s*\(\s*'([\s\S]*)',\s*(\d+),\s*(\d+),\s*'([\s\S]*?)'\.split\('\|'\)""")
            .find(packed) ?: return null
        var payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = m.groupValues[2].toInt()
        var count = m.groupValues[3].toInt()
        val dict = m.groupValues[4].split('|')
        while (count-- > 0) {
            if (dict.getOrNull(count).orEmpty().isNotEmpty()) {
                val token = Regex("\\b" + Regex.escape(baseN(count, radix)) + "\\b")
                payload = token.replace(payload) { dict[count] }
            }
        }
        payload
    }.getOrNull()

    private fun hexEscapesToString(value: String): String =
        Regex("""\\x([0-9a-fA-F]{2})""").replace(value) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\/", "/")

    /**
     * VidMoxy embeds can expose a hex-escaped file directly or hide it in a
     * twice-packed P.A.C.K.E.R block. Return only a real HTTP(S) HLS URL.
     */
    private suspend fun resolveVidMoxy(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit): String? {
        val html = app.get(embedUrl, referer = "$mainUrl/").text
        emitTurkishSubtitles(html, embedUrl, subtitleCallback)
        val direct = Regex(""""file"\s*:\s*"([^"]*\\x[^"]*)"""")
            .find(html)?.groupValues?.get(1)
        var master = direct?.let { hexEscapesToString(it) }.orEmpty()

        if (master.isBlank()) {
            val packed = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""")
                .find(html)?.groupValues?.get(1)
            if (packed != null) {
                val once = unpackPacker(packed)
                val twice = once?.let { unpackPacker(it) }
                val decoded = (twice ?: once).orEmpty().replace("\\\\", "\\")
                val file = Regex("""file"\s*:\s*"([^"]*)"""").find(decoded)?.groupValues?.get(1)
                if (file != null) master = hexEscapesToString(file)
            }
        }
        return master.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /**
     * TurboImgz embeds expose the playable HLS master in a JavaScript file field.
     * The embed page itself is not a media URL and must never be emitted as a stream.
     */
    private suspend fun resolveTurboImgz(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit): String? {
        val html = app.get(embedUrl, referer = "$mainUrl/").text
        emitTurkishSubtitles(html, embedUrl, subtitleCallback)
        val raw = Regex("""(?i)["']?file["']?\s*:\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?: return null
        val normalized = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> return null
        }
        return normalized.takeIf { it.startsWith("http") }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd")
    }

    private fun collectScxText(node: JsonNode, out: MutableList<String>) {
        when {
            node.isTextual -> node.asText().takeIf { it.isNotBlank() }?.let(out::add)
            node.isArray -> node.forEach { collectScxText(it, out) }
            node.isObject -> node.fields().forEachRemaining { (_, child) -> collectScxText(child, out) }
        }
    }

    private fun parseScx(html: String): List<String> {
        val marker = Regex("""\bscx\s*=\s*\{""").find(html) ?: return emptyList()
        val start = html.indexOf('{', marker.range.first)
        var depth = 0
        var quoted = false
        var quote = '\u0000'
        var escaped = false
        var end = -1
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quoted = false
                continue
            }
            if (c == '"' || c == '\'') {
                quoted = true
                quote = c
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) {
                    end = i + 1
                    break
                }
            }
        }
        if (end <= start) return emptyList()

        val objectText = html.substring(start, end)
        // Match Neo's working approach: scx is a structured object and playable
        // source values live under each provider's sx.t field, not in every
        // quoted string in the object (keys and labels are not source URLs).
        val root = runCatching { mapper.readTree(objectText) }.getOrNull()
        if (root != null && root.isObject) {
            val values = mutableListOf<String>()
            root.fields().forEachRemaining { (_, provider) ->
                val t = provider.path("sx").path("t")
                if (!t.isMissingNode && !t.isNull) collectScxText(t, values)
            }
            if (values.isNotEmpty()) return values.distinct()
        }

        // Some mirrors emit slightly non-JSON scx. Keep a narrow fallback for
        // quoted payload values, but never stop the main path from trying them.
        return Regex("""["']([^"'\\]{8,})["']""")
            .findAll(objectText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /**
     * Resolve the site's Atom/RapidVid embed directly. The provider's scx value
     * only points to the embed page; it is not itself a playable stream.
     */
    private suspend fun resolveRapidVid(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit): String? {
        val uri = URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"
        val html = app.get(embedUrl, referer = "$mainUrl/").text
        emitTurkishSubtitles(html, embedUrl, subtitleCallback)

        fun decodeImgz(blob: String): String? = runCatching {
            val reversed = blob.reversed()
            val first = String(Base64.getMimeDecoder().decode(
                reversed.replace('-', '+').replace('_', '/').let { s ->
                    s + "=".repeat((4 - s.length % 4) % 4)
                }
            ), StandardCharsets.ISO_8859_1)
            val shifted = buildString {
                first.forEachIndexed { i, ch ->
                    val key = "K9L"[i % 3].code % 5 + 1
                    append((ch.code - key).toChar())
                }
            }
            val normalized = shifted.replace('-', '+').replace('_', '/')
            String(Base64.getMimeDecoder().decode(normalized + "=".repeat((4 - normalized.length % 4) % 4)), StandardCharsets.UTF_8)
        }.getOrNull()

        val p8 = Regex("""window\s*\.\s*_p8\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        if (p8 != null) {
            val json = decodeImgz(p8)
            val master = json?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                ?.let { it.path("cm").asText().ifBlank { it.path("tm").asText() } }
            if (!master.isNullOrBlank() && master.startsWith("http")) return master
        }

        // Newer RapidVid embeds put the HLS master in jwSetup.sources via av()/_.()
        val encoded = Regex("""['"]file['"]\s*:\s*(?:av|_)\(\s*['"]([^'"]+)['"]\s*\)""")
            .find(html)?.groupValues?.get(1)
        if (encoded != null) {
            val master = decodeImgz(encoded)
            if (!master.isNullOrBlank() && master.startsWith("http")) return master
        }
        val plain = Regex("""jwSetup\s*\.\s*sources\s*=\s*\[[\s\S]*?['"]file['"]\s*:\s*['"](https?://[^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)
        if (!plain.isNullOrBlank()) return plain
        return null
    }

    private fun decodeEntry(s: String): String? = runCatching {
        val rot = s.map { c -> when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        } }.joinToString("")
        val normalized = rot.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        String(Base64.getMimeDecoder().decode(padded), StandardCharsets.UTF_8)
            .takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("//") }
    }.getOrNull()

    private fun decodeAny(value: String): String? {
        val direct = value.trim().replace("\\\\/", "/")
        if (direct.startsWith("https://") || direct.startsWith("http://") || direct.startsWith("//")) return direct
        decodeEntry(value)?.let { return it }
        // Sites may use plain Base64, URL-safe Base64, or ROT13 + Base64.
        val candidates = listOf(value, value.map { ch -> when (ch) {
            in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
            else -> ch
        } }.joinToString(""))
        for (candidate in candidates) runCatching {
            val normalized = candidate.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.getMimeDecoder().decode(padded), StandardCharsets.UTF_8)
                .replace("\\\\/", "/")
        }.getOrNull()?.let { decoded ->
            Regex("""https?://[^"'\\s<>]+|//[^"'\\s<>]+""").find(decoded)?.value?.let { return it }
        }
        return Regex("""https?://[^"'\\s<>]+|//[^"'\\s<>]+""").find(value)?.value
    }

    private fun isMovieUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val slug = path.lowercase()
        val nonMoviePath = listOf(
            "kategori", "category", "categories", "genre", "turler", "film-turleri",
            "film-listeleri", "seri-filmler", "aile-filmleri", "aksiyon-filmleri",
            "en-cok-izlenen", "en-cok-izlenen-filmler", "autocomplete", "search",
            "arama", "etiket", "tag", "oyuncu", "yonetmen", "sayfa"
        )
        val looksLikeTaxonomy = path.trim('/').split('/').lastOrNull().orEmpty().lowercase() in setOf(
            "filmizle", "kategori", "kategoriler", "turler", "film-turleri", "film-listeleri",
            "aile-filmleri", "aksiyon-filmleri", "komedi-filmleri", "korku-filmleri",
            "animasyon-filmleri", "bilim-kurgu-filmleri", "yeni-filmler", "en-cok-izlenen-filmler"
        )
        return host.endsWith("fullhdfilmizlesene.now", true) && path.isNotBlank() && path != "/" &&
            !looksLikeTaxonomy && nonMoviePath.none { slug.contains(it) }
    }

    private fun isTurkishSubtitleLabel(label: String?): Boolean {
        val value = label.orEmpty().trim().lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        return value == "tr" || value.startsWith("tr-") ||
            value.contains("turkish") || value.contains("turkce")
    }

    /** Emit only real Turkish subtitle tracks found in an embed player's markup. */
    private suspend fun emitTurkishSubtitles(
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Players often escape URLs in JS, or keep subtitle tracks in packed scripts.
        val normalized = (unpackPacker(html) ?: html)
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&#038;", "&", ignoreCase = true)
        val candidates = linkedMapOf<String, String>()
        val turkishMarker = Regex("""(?i)(?:\btr\b|\btur\b|tr[-_](?:tr|tur)|tur(?:kish)?[-_.](?:\d+|default)|turkish|turkce|turkçe|türkçe|turkısh|\bforced\b.*(?:\btr\b|tur)|(?:\btr\b|tur).*\bforced\b|lang(?:uage)?[=:/"']+(?:tr|tur)\b|srclang[=:/"']+(?:tr|tur)\b)""")
        val englishMarker = Regex("""(?i)(?:\ben\b|\beng\b|english|ingilizce|\bing\b|en[-_]us|en[-_]gb|srclang[=:/"']+en\b|lang(?:uage)?[=:/"']+en\b)""")

        fun add(raw: String?, label: String?, context: String = "") {
            if (raw.isNullOrBlank()) return
            val clean = raw.trim().trim('\'', '"')
                .replace("\\\\/", "/").replace("\\/", "/")
                .replace("&amp;", "&", ignoreCase = true)
            val absolute = runCatching { URI(pageUrl).resolve(URI(clean)).toASCIIString() }.getOrNull() ?: return
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
            val path = uri.path.orEmpty().lowercase()
            if (!Regex("""(?i)\.(srt|vtt|ass|ssa|ttml|xml)$""").containsMatchIn(path)) return

            val nearby = (label.orEmpty() + " " + absolute + " " + context)
                .lowercase()
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
            if (englishMarker.containsMatchIn(nearby) && !turkishMarker.containsMatchIn(nearby)) return
            candidates.putIfAbsent(absolute, nearby)
        }

        fun looksLikeTurkishSubtitle(text: String, url: String): Boolean {
            // Strip markup/timestamps so language scoring uses dialogue rather than player metadata.
            val sample = text.take(20000).lowercase()
                .replace(Regex("""(?is)<[^>]+>"""), " ")
                .replace(Regex("""(?m)^\s*(?:\d+\s*)?(?:\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->.*|WEBVTT.*|\d+)\s*$"""), " ")
            val normalized = sample.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
            val turkishChars = Regex("[çğıöşü]").findAll(sample).count()
            val commonTurkish = Regex("""\b(ve|bir|bu|şey|için|ama|değil|ben|sen|biz|siz|onlar|ne|neden|nasıl|biliyor|musun|mi|mı|mu|mü|evet|hayır|tamam|oldu|gibi|çok|daha|var|yok|kadar|şimdi|burada|orada|lütfen|teşekkür|istiyorum|istemiyorum|yapabilir|olabilir|gelecek|git|gel|bak|bana|sana|onu|bunu|çünkü|her|şu|olan|olarak)\b""", RegexOption.IGNORE_CASE)
                .findAll(sample).count()
            val commonEnglish = Regex("""\b(the|and|you|that|what|this|with|have|for|not|are|your|was|were|hello|yes|no|okay|know|think|because|about|there|their|they|them|then|when|where|who|how|why|would|could|should|from|just|like|want|well|really|right|here|she|him|her|we|our|us|but|can|did|does|got|has|had|will|all|one|out|too|very)\b""", RegexOption.IGNORE_CASE)
                .findAll(normalized).count()
            // Require dialogue evidence. Turkish diacritics alone are not enough to classify a track.
            val languageLooksTurkish = (commonTurkish >= 2 && commonTurkish > commonEnglish) ||
                (turkishChars >= 4 && commonEnglish <= commonTurkish)
            // imgz.me commonly names its Turkish track tur-1-default.vtt. Use that
            // explicit filename as a fallback when the first cues contain little text,
            // but still reject it when English dialogue clearly dominates.
            val turkishFilename = Regex("""(?i)(?:^|[/_.-])tur(?:kish|kce|kçe)?[-_.](?:\\d+|default)(?:[-_.]|\\.)|/tur-\\d+-default\\.vtt(?:$|\\?)""")
                .containsMatchIn(url)
            return languageLooksTurkish || (turkishFilename && commonTurkish >= 1 && commonEnglish <= 2)
        }
        // Standard HTML5 tracks, including data-src variants.
        val trackTag = Regex("""(?is)<track\b([^>]+)>""")
        val attrPattern = Regex("""(?is)([\w-]+)\s*=\s*["']([^"']*)["']""")
        for (match in trackTag.findAll(normalized)) {
            val attrs = attrPattern.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val kind = attrs["kind"].orEmpty().lowercase()
            if (kind.isNotBlank() && kind !in setOf("subtitles", "captions")) continue
            add(attrs["src"] ?: attrs["data-src"], attrs["srclang"] ?: attrs["label"], match.value)
        }

        val filePattern = Regex("""(?is)["']?(?:file|src|url|subtitle|captions)["']?\s*[:=]\s*["']([^"']+)["']""")
        for (match in filePattern.findAll(normalized)) {
            val from = (match.range.first - 400).coerceAtLeast(0)
            val to = (match.range.last + 400).coerceAtMost(normalized.length)
            val context = normalized.substring(from, to)
            val label = Regex("""(?is)["']?(?:label|srclang|language|lang)["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(context)?.groupValues?.getOrNull(1)
            add(match.groupValues.getOrNull(1), label, context)
        }

        val directUrlPattern = Regex("""(?i)(?:https?:)?//[^"'\s<>]+?\.(?:srt|vtt|ass|ssa|ttml|xml)(?:\?[^"'\s<>]*)?|(?:/[A-Za-z0-9_./%+-]+)\.(?:srt|vtt|ass|ssa|ttml|xml)(?:\?[^"'\s<>]*)?""")
        for (match in directUrlPattern.findAll(normalized)) {
            val from = (match.range.first - 250).coerceAtLeast(0)
            val to = (match.range.last + 250).coerceAtMost(normalized.length)
            add(match.value, null, normalized.substring(from, to))
        }

        val scriptUrls = Regex("""(?is)<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""")
            .findAll(normalized)
            .mapNotNull { match -> runCatching { URI(pageUrl).resolve(URI(match.groupValues[1])).toASCIIString() }.getOrNull() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct().take(30).toList()

        for (scriptUrl in scriptUrls) {
            val script = runCatching { app.get(scriptUrl, referer = pageUrl).text }.getOrNull() ?: continue
            val scriptText = (unpackPacker(script) ?: script)
                .replace("\\\\/", "/").replace("\\/", "/")
                .replace("\\u002f", "/", ignoreCase = true)
                .replace("\\u003a", ":", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
            for (match in filePattern.findAll(scriptText)) {
                val from = (match.range.first - 400).coerceAtLeast(0)
                val to = (match.range.last + 400).coerceAtMost(scriptText.length)
                val context = scriptText.substring(from, to)
                val label = Regex("""(?is)["']?(?:label|srclang|language|lang)["']?\s*[:=]\s*["']([^"']+)["']""")
                    .find(context)?.groupValues?.getOrNull(1)
                add(match.groupValues.getOrNull(1), label, context)
            }
            for (match in directUrlPattern.findAll(scriptText)) {
                val from = (match.range.first - 250).coerceAtLeast(0)
                val to = (match.range.last + 250).coerceAtMost(scriptText.length)
                add(match.value, null, scriptText.substring(from, to))
            }
        }

        for ((url, metadata) in candidates) {
            // Never trust a filename/nearby JS label by itself: sites sometimes attach the wrong label.
            // Fetch and classify every candidate, including those marked tr/turkish in the player.
            val body = runCatching { app.get(url, referer = pageUrl).text }.getOrNull() ?: continue
            if (!looksLikeTurkishSubtitle(body, url)) continue
            subtitleCallback(newSubtitleFile("Türkçe", url) {
                this.headers = mapOf("Referer" to pageUrl)
            })
        }
    }

    private fun isCandidateEmbed(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return false
        // YouTube's quality variants are not real FullHDFilmizlesene source choices.
        if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        ) return false
        return true
    }

    private fun Element.toSearchResult(href: String): SearchResponse? {
        val card = closest("article, li, .film, .movie, .card, .item") ?: this
        val image = selectFirst("img") ?: card.selectFirst("img")
        val title = listOf(selectFirst("span.film-title")?.text(), card.selectFirst("span.film-title")?.text(),
            selectFirst("h2, h3, h4, [class*=title]")?.text(), card.selectFirst("h2, h3, h4, [class*=title]")?.text(),
            attr("title"), image?.attr("alt"), attr("aria-label"), text())
            .map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() && it.length > 1 } ?: return null
        return newMovieSearchResponse(title.decodeHtml(), href, TvType.Movie) {
            posterUrl = image?.let { imageUrl(it) }
        }
    }

    private fun imageUrl(img: Element): String? =
        listOf("data-src", "data-lazy-src", "data-original", "src").asSequence()
            .map { img.attr(it).trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:image", true) }
            ?.let { fixUrlNull(it) }

    private fun String.urlEncoded() = java.net.URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    private fun String.decodeHtml() = org.jsoup.parser.Parser.unescapeEntities(this, false)
}

/*
 * SPDX-License-Identifier: MIT
 * Resolves the CloseLoad/Rapid JWPlayer source variable with bounded JavaScript.
 */
package com.kra.cloudstream.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.net.URI

class FilmMakinesiPlayerResolver : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = playerBase(url) ?: return
        val playerName = playerName(url)
        // Keep the native CloudStream User-Agent. Only add the same iframe
        // navigation metadata used by the known-working reference implementation.
        val html = runCatching {
            app.get(
                url,
                referer = referer ?: "$base/",
                headers = mapOf(
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate"
                )
            ).text
        }.getOrNull() ?: return

        readSubtitles(html, base).forEach { (language, subtitleUrl) ->
            subtitleCallback(newSubtitleFile(language, subtitleUrl))
        }

        val links = resolveSourceLinks(html)
        if (links.isEmpty()) {
            Log.d("FilmMakinesi", "$playerName source could not be decoded: $url")
            return
        }
        links.forEach { link ->
            callback(
                newExtractorLink(playerName, playerName, link, ExtractorLinkType.M3U8) {
                    referer = "$base/"
                    quality = Qualities.Unknown.value
                }
            )
        }
    }

    companion object {
        internal fun playerBase(url: String): String? = runCatching {
            URI(url).let { uri ->
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) null
                else "${uri.scheme}://${uri.host}"
            }
        }.getOrNull()

        internal fun playerName(url: String): String {
            val base = playerBase(url).orEmpty()
            return when {
                url.contains("/rplayer/") || url.contains("/playerr/") -> "Rapidrame"
                base.substringAfter("://").startsWith("rapid", true) -> "Rapid"
                else -> "CloseLoad"
            }
        }

        internal fun sourceVariable(html: String): String? = Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][\w$]*)\s*[,}]"""
        ).find(html)?.groupValues?.getOrNull(1)

        internal fun resolveSourceLinks(html: String): List<String> {
            val variable = sourceVariable(html)
            val found = linkedSetOf<String>()
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["'](https?://[^"']+)["']""")
                .findAll(html).forEach { match -> cleanLink(match.groupValues[1])?.let(found::add) }

            if (!variable.isNullOrBlank()) {
                val scripts = Jsoup.parse(html).select("script")
                    .map { it.data().ifBlank { it.html() } }
                    .filter {
                        it.contains("eval(function(p,a,c,k,e,d)") ||
                        it.contains(".join('')") ||
                        it.contains(".join(\"\")") ||
                        Regex("""\\b${Regex.escape(variable)}\\b""").containsMatchIn(it)
                    }
                if (scripts.isNotEmpty()) {
                    val cx = TimedContextFactory.enterContext()
                    try {
                        cx.optimizationLevel = -1
                        cx.instructionObserverThreshold = 10_000
                        cx.putThreadLocal("fmStarted", System.currentTimeMillis())
                        val scope = cx.initSafeStandardObjects()
                        cx.evaluateString(scope, POLYFILL, "fm-polyfill", 1, null)

                        scripts.forEachIndexed { index, script ->
                            if (script.length > MAX_SCRIPT_LENGTH) return@forEachIndexed
                            val packed = if (script.contains("eval(function(p,a,c,k,e,d)")) {
                                runCatching { getPacked(script) }.getOrNull()
                            } else null
                            val executable = if (!packed.isNullOrBlank()) {
                                val evaluated = runCatching {
                                    Context.toString(
                                        cx.evaluateString(scope, packed.removePrefix("eval"), "fm-packed-$index", 1, null)
                                    )
                                }.getOrNull()?.takeIf { it.isNotBlank() && it != "undefined" }
                                evaluated ?: runCatching { JsUnpacker(packed).unpack() }.getOrNull()
                            } else script
                            if (!executable.isNullOrBlank()) {
                                runCatching { cx.evaluateString(scope, executable, "fm-script-$index", 1, null) }
                            }
                        }

                        val value = scope.get(variable, scope)
                        if (value != null && value != Scriptable.NOT_FOUND) {
                            cleanLink(Context.toString(value))?.let(found::add)
                        }
                    } catch (e: Throwable) {
                        Log.d("FilmMakinesi", "JavaScript source evaluation failed: ${e.message}")
                    } finally {
                        Context.exit()
                    }
                }
            }

            // Legacy player variant: file_link contains a Base64 URL after unpacking.
            val unpacked = runCatching { getAndUnpack(html) }.getOrNull().orEmpty()
            val encoded = Regex("""file_link\s*=\s*["']([^"']+)["']""")
                .find(unpacked)?.groupValues?.getOrNull(1)
            val legacy = encoded?.let { runCatching { base64Decode(it) }.getOrNull() }
            cleanLink(legacy)?.let(found::add)
            return found.toList()
        }

        private fun cleanLink(raw: String?): String? {
            val value = raw?.trim().orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\u003a", ":", ignoreCase = true)
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            return value.takeIf { uri.host?.isNotBlank() == true }
        }

        private fun readSubtitles(html: String, base: String): List<Pair<String, String>> {
            val tracks = linkedMapOf<String, Pair<String, String>>()
            Regex(
                """\{[^{}]*?"file"\s*:\s*"([^"]+?\.(?:vtt|srt)[^"]*)"[^{}]*?"label"\s*:\s*"([^"]*)"[^{}]*?\}"""
            ).findAll(html).forEach { match ->
                val rawUrl = match.groupValues[1].replace("\\/", "/")
                val url = if (rawUrl.startsWith("http", true)) rawUrl else "$base/${rawUrl.trimStart('/')}"
                val label = Regex("""\\u([0-9a-fA-F]{4})""")
                    .replace(match.groupValues[2]) { it.groupValues[1].toInt(16).toChar().toString() }
                tracks.putIfAbsent(url, label to url)
            }
            Jsoup.parse(html).select("track[src]").forEach { track ->
                val rawUrl = track.attr("src")
                val url = runCatching { URI(base).resolve(URI(rawUrl)).toString() }.getOrNull()
                    ?: return@forEach
                val label = track.attr("label").ifBlank { track.attr("srclang") }
                    .ifBlank { "Subtitle" }
                tracks.putIfAbsent(url, label to url)
            }
            return tracks.values.toList()
        }

        private val TimedContextFactory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                setInterpretedMode(true)
                instructionObserverThreshold = 10_000
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val started = cx.getThreadLocal("fmStarted") as? Long ?: return
                if (System.currentTimeMillis() - started > MAX_RUNTIME_MS) {
                    throw IllegalStateException("Player JavaScript execution timed out")
                }
            }
        }

        private const val MAX_SCRIPT_LENGTH = 500_000
        private const val MAX_RUNTIME_MS = 5_000L
        private const val POLYFILL = """
            var window = this; var self = this;
            var document = {};
            var navigator = { userAgent: "" };
            var _b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
            function atob(input) {
              var str = String(input).replace(/[=]+$/, "").replace(/[^A-Za-z0-9+\\/]/g, "");
              var output = "";
              for (var bc = 0, bs = 0, buffer, idx = 0; (buffer = str.charAt(idx++)); ) {
                buffer = _b64.indexOf(buffer);
                if (~buffer) { bs = bc % 4 ? bs * 64 + buffer : buffer; if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6))); }
              }
              return output;
            }
            function btoa(input) {
              var str = String(input), output = "";
              for (var block, charCode, idx = 0, map = _b64; str.charAt(idx | 0) || ((map = "="), idx % 1); output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                charCode = str.charCodeAt((idx += 3 / 4));
                block = (block << 8) | charCode;
              }
              return output;
            }
        """
    }
}

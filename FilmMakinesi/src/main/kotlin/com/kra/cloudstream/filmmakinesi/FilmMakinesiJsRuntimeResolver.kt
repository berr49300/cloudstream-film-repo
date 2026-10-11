/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Bounded JavaScript fallback for players that compute the final source in a
 * runtime variable. Safe standard objects disable Java interop; execution is
 * instruction/time limited and only the resulting media URL is returned.
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.net.URI

internal object FilmMakinesiJsRuntimeResolver {
    private val sourceVariable = Regex(
        """(?is)sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][\w$]*)"""
    )
    private val fileLink = Regex("""(?is)file_link\s*=\s*["']([^"']+)["']""")

    fun extractStreamUrls(html: String, baseUrl: String): List<String> {
        // The file variable can be declared in a separate script from the
        // sources array. Discover it from the full player document first.
        val visibleVariables = sourceVariable.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .distinct()
            .toList()
        val scriptTexts = Jsoup.parse(html, baseUrl).select("script")
            .map { it.data().ifBlank { it.html() } }
            .filter { script ->
                sourceVariable.containsMatchIn(script) ||
                    script.contains("sources", ignoreCase = true) ||
                    script.contains(".join('')") || script.contains(".join(\"\")") ||
                    script.contains("file_link", ignoreCase = true) ||
                    script.contains("eval(function(p,a,c,k,e,d)") ||
                    script.contains("atob(", ignoreCase = true) ||
                    visibleVariables.any { variable ->
                        Regex("""\b${Regex.escape(variable)}\b""").containsMatchIn(script)
                    }
            }
        val candidates = linkedSetOf<String>()
        scriptTexts.forEach { script ->
            candidates += script
            // Keep the local decoder as a fast path, but also use CloudStream's
            // own P.A.C.K.E.R. implementation. The site changes wrapper details
            // often enough that a narrow regex alone can miss the source script.
            FilmMakinesiJsPacker.unpackAll(script).forEach(candidates::add)
            val packed = runCatching { getPacked(script) }.getOrNull()
            if (!packed.isNullOrBlank()) {
                val unpacked = runCatching { JsUnpacker(packed).unpack() }.getOrNull()
                if (!unpacked.isNullOrBlank()) candidates.add(unpacked)
            }
        }
        FilmMakinesiJsPacker.unpackAll(html).forEach(candidates::add)
        val cloudStreamUnpacked = runCatching { getAndUnpack(html) }.getOrNull()
        if (!cloudStreamUnpacked.isNullOrBlank() && cloudStreamUnpacked != html) {
            candidates.add(cloudStreamUnpacked)
            FilmMakinesiJsPacker.unpackAll(cloudStreamUnpacked).forEach(candidates::add)
        }
        if (candidates.isEmpty()) return emptyList()

        val wantedVariables = (candidates.asSequence() + sequenceOf(html))
            .mapNotNull { sourceVariable.find(it)?.groupValues?.getOrNull(1) }
            .distinct()
            .toList()
        if (wantedVariables.isEmpty() && candidates.none {
                fileLink.containsMatchIn(it) ||
                    it.contains("eval(function(p,a,c,k,e,d)") ||
                    it.contains("sources", ignoreCase = true)
            }) return emptyList()

        val cx = limitedContextFactory.enterContext()
        return try {
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = 10_000
            cx.putThreadLocal("filmMakinesiStartedAt", System.currentTimeMillis())
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, POLYFILL, "player-polyfill", 1, null)

            candidates.forEachIndexed { index, script ->
                if (script.length > MAX_SCRIPT_LENGTH) return@forEachIndexed
                // Match the working NeoWatch strategy: execute the player
                // wrapper in bounded Rhino instead of discarding P.A.C.K.E.R. code.
                val relevant = script.contains("sources", ignoreCase = true) ||
                    script.contains(".join('')") || script.contains(".join(\"\")") ||
                    script.contains("file_link", ignoreCase = true) ||
                    script.contains("eval(function(p,a,c,k,e,d)") ||
                    script.contains("atob(", ignoreCase = true) ||
                    wantedVariables.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(script) }
                if (relevant) {
                    val packed = if (script.contains("eval(function(p,a,c,k,e,d)")) {
                        runCatching { getPacked(script) }.getOrNull()
                    } else null
                    if (!packed.isNullOrBlank()) {
                        // The reference resolver evaluates the unpacked payload
                        // in the same Rhino scope so variables shared by player
                        // scripts remain visible.
                        val decoded = runCatching {
                            Context.toString(
                                cx.evaluateString(scope, packed.removePrefix("eval"), "player-packed-$index", 1, null)
                            )
                        }.getOrNull()?.takeIf { it.isNotBlank() && it != "undefined" }
                            ?: runCatching { JsUnpacker(packed).unpack() }.getOrNull()
                        if (!decoded.isNullOrBlank()) {
                            runCatching { cx.evaluateString(scope, decoded, "player-unpacked-$index", 1, null) }
                        } else {
                            runCatching { cx.evaluateString(scope, script, "player-script-$index", 1, null) }
                        }
                    } else {
                        runCatching { cx.evaluateString(scope, script, "player-script-$index", 1, null) }
                    }
                }
            }

            val found = linkedSetOf<String>()
            wantedVariables.forEach { variable ->
                val value = scope.get(variable, scope)
                if (value != null && value != Scriptable.NOT_FOUND) {
                    normalizeCandidate(Context.toString(value), baseUrl)?.let(found::add)
                }
            }

            // Some player versions use an obfuscated global variable name.
            // After executing the source/player scripts, inspect URL-valued
            // globals whose names indicate media/source data.
            scope.ids.forEach { id ->
                val variableName = id.toString()
                if (!Regex("""(?i)(file|src|source|stream|video|media|link|url|^cm$|^tm$)""")
                        .containsMatchIn(variableName)) return@forEach
                val value = scope.get(variableName, scope)
                if (value is String &&
                    (value.startsWith("http://", true) || value.startsWith("https://", true) ||
                        value.startsWith("//"))
                ) {
                    normalizeCandidate(value, baseUrl)?.let(found::add)
                }
            }

            candidates.forEach { script ->
                fileLink.findAll(script).forEach { match ->
                    val decoded = runCatching {
                        val token = match.groupValues[1]
                        val padded = token + "=".repeat((4 - token.length % 4) % 4)
                        String(decodeBase64(padded) ?: return@runCatching null, Charsets.UTF_8)
                    }.getOrNull()
                    decoded?.let { normalizeCandidate(it, baseUrl) }?.let(found::add)
                }
            }
            found.toList()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            Context.exit()
        }
    }

    private fun normalizeCandidate(value: String, baseUrl: String): String? {
        val cleaned = value.trim().removeSurrounding("\"").removeSurrounding("'")
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
        if (cleaned.isBlank() || cleaned == "undefined" || cleaned == "null") return null
        val absolute = runCatching { URI(baseUrl).resolve(URI(cleaned)).toASCIIString() }.getOrNull() ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme !in setOf("http", "https") || host.isBlank()) return null
        if (FilmMakinesiSourceRules.isYouTubeHost(host)) return null
        if (host.contains("google") || host.contains("facebook") || host.contains("doubleclick")) return null
        return absolute
    }

    private val limitedContextFactory = LimitedContextFactory()

    private fun decodeBase64(value: String): ByteArray? {
        val input = value.filterNot(Char::isWhitespace).trimEnd('=').replace('-', '+').replace('_', '/')
        if (input.isEmpty()) return byteArrayOf()
        if (input.length % 4 == 1) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = java.io.ByteArrayOutputStream(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in input) {
            val digit = alphabet.indexOf(ch)
            if (digit < 0) return null
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xff)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return output.toByteArray()
    }

    private class LimitedContextFactory : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            setInterpretedMode(true)
            instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val started = cx.getThreadLocal("filmMakinesiStartedAt") as? Long ?: return
            if (System.currentTimeMillis() - started > MAX_RUNTIME_MS) {
                throw IllegalStateException("Player JavaScript execution timed out")
            }
        }
    }

    private const val MAX_SCRIPT_LENGTH = 500_000
    private const val MAX_RUNTIME_MS = 3_000L

    private const val POLYFILL = """
        var window = this; var self = this;
        var document = { createElement: function(){ return {}; } };
        var navigator = { userAgent: "" };
        var _b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
        function atob(input) {
            var str = String(input).replace(/[=]+$/, "").replace(/[^A-Za-z0-9+\/]/g, "");
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

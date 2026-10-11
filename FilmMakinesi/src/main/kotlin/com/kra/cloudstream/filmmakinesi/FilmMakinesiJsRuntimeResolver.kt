/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Bounded JavaScript fallback for players that compute the final source in a
 * runtime variable. Safe standard objects disable Java interop; execution is
 * instruction/time limited and only the resulting media URL is returned.
 */
package com.kra.cloudstream.filmmakinesi

import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.net.URI
import java.util.Base64

internal object FilmMakinesiJsRuntimeResolver {
    private val sourceVariable = Regex(
        """(?is)sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*([A-Za-z_$][\\w$]*)"""
    )
    private val fileLink = Regex("""(?is)file_link\\s*=\\s*["']([^"']+)["']""")

    fun extractStreamUrls(html: String, baseUrl: String): List<String> {
        val scriptTexts = Jsoup.parse(html, baseUrl).select("script")
            .map { it.data().ifBlank { it.html() } }
            .filter { script ->
                script.contains("sources", ignoreCase = true) ||
                    script.contains(".join('')") || script.contains(".join(\"\")") ||
                    script.contains("file_link", ignoreCase = true) ||
                    script.contains("eval(function(p,a,c,k,e,d)")
            }
        val candidates = linkedSetOf<String>()
        scriptTexts.forEach { script ->
            candidates += script
            FilmMakinesiJsPacker.unpackAll(script).forEach(candidates::add)
        }
        FilmMakinesiJsPacker.unpackAll(html).forEach(candidates::add)
        if (candidates.isEmpty()) return emptyList()

        val wantedVariables = candidates.mapNotNull { sourceVariable.find(it)?.groupValues?.getOrNull(1) }
            .distinct()
        if (wantedVariables.isEmpty() && candidates.none { fileLink.containsMatchIn(it) }) return emptyList()

        val cx = LimitedContextFactory.enterContext()
        return try {
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = 10_000
            cx.putThreadLocal("filmMakinesiStartedAt", System.currentTimeMillis())
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, POLYFILL, "player-polyfill", 1, null)

            candidates.forEachIndexed { index, script ->
                if (script.length > MAX_SCRIPT_LENGTH) return@forEachIndexed
                if (script.contains("eval(function(p,a,c,k,e,d)")) return@forEachIndexed
                val relevant = script.contains("sources", ignoreCase = true) ||
                    script.contains(".join('')") || script.contains(".join(\"\")") ||
                    script.contains("file_link", ignoreCase = true) ||
                    wantedVariables.any { Regex("""\\b${Regex.escape(it)}\\b""").containsMatchIn(script) }
                if (relevant) {
                    runCatching { cx.evaluateString(scope, script, "player-script-$index", 1, null) }
                }
            }

            val found = linkedSetOf<String>()
            wantedVariables.forEach { variable ->
                val value = scope.get(variable, scope)
                if (value != null && value != Scriptable.NOT_FOUND) {
                    normalizeCandidate(Context.toString(value), baseUrl)?.let(found::add)
                }
            }
            candidates.forEach { script ->
                fileLink.findAll(script).forEach { match ->
                    val decoded = runCatching {
                        val token = match.groupValues[1]
                        val padded = token + "=".repeat((4 - token.length % 4) % 4)
                        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
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
            .replace("\\\\/", "/")
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

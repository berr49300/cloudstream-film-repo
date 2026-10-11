/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmMakinesiJsPackerTest {
    @Test
    fun decodesCommonPackerPayloadWithoutExecutingIt() {
        val packed = """eval(function(p,a,c,k,e,d){return p;}('0.1(\'2 3!\');',62,4,'console|log|hello|world'.split('|'),0,{}))"""

        assertEquals(
            listOf("console.log('hello world!');"),
            FilmMakinesiJsPacker.unpackAll(packed)
        )
    }

    @Test
    fun resolvesDirectMediaPathsAndPreservesStreamTokens() {
        val html = """jwplayer.setup({file: "/media/master.m3u8?token=abc123"});"""

        assertEquals(
            listOf("https://closeload.filmmakinesi.to/media/master.m3u8?token=abc123"),
            FilmMakinesiStreamParser.extractStreamUrls(
                html,
                "https://closeload.filmmakinesi.to/embed/abc"
            )
        )
    }

    @Test
    fun extractsMediaUrlThatAppearsOnlyAfterPackerUnpacking() {
        val packed = """eval(function(p,a,c,k,e,d){return p;}('0',62,1,'file:"/media/master.m3u8"'.split('|'),0,{}))"""

        assertEquals(
            listOf("https://closeload.filmmakinesi.to/media/master.m3u8"),
            FilmMakinesiStreamParser.extractStreamUrls(
                packed,
                "https://closeload.filmmakinesi.to/embed/abc"
            )
        )
    }

    @Test
    fun keepsOpaqueVideoSourceUrlsWithoutFileExtensions() {
        val html = """<video><source src="https://cdn.example.test/play/abc123?token=xyz" type="application/vnd.apple.mpegurl"></video>"""

        assertEquals(
            listOf("https://cdn.example.test/play/abc123?token=xyz"),
            FilmMakinesiStreamParser.extractStreamUrls(
                html,
                "https://closeload.filmmakinesi.to/embed/abc"
            )
        )
    }

    @Test
    fun resolvesSourceVariableComputedByPlayerJavascript() {
        val html = """
            <script>
                var encodedFile = "aHR0cHM6Ly9jZG4uZXhhbXBsZS50ZXN0L3N0cmVhbS9tYXN0ZXIubTN1OA==";
                var resolvedFile = atob(encodedFile);
                var player = { sources: [{ file: resolvedFile }] };
            </script>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example.test/stream/master.m3u8"),
            FilmMakinesiStreamParser.extractStreamUrls(
                html,
                "https://closeload.filmmakinesi.to/embed/abc"
            )
        )
    }

    @Test
    fun decodesLegacyCloseLoadPayloadWithReferenceStateTransitions() {
        val expected = "https://cdn.example.test/movie/master.m3u8"
        val seedKey = "legacy-key"
        val operations = "3"
        val seed = seedKey.fold(0) { acc, ch -> (acc * 37 + ch.code) % 241 }
        val mask = seedKey.foldIndexed(0) { index, acc, ch ->
            (acc + ((ch.code shl 1) xor index)) and 255
        }
        val initial = (seed * 3 + mask) % 256
        val stride = (mask % 11) + 5

        // Invert the reference decoder's rolling XOR.
        var rolling = initial
        val xorEncoded = buildString(expected.length) {
            expected.forEach { ch ->
                rolling = (rolling * 5 + stride) % 256
                val code = ch.code xor rolling
                append(code.toChar())
                rolling = (rolling + code) % 256
            }
        }

        // Invert the reference decoder's deterministic shuffle.
        var state = ((mask * 251 + seed) % 65519) + 1
        val swaps = IntArray(xorEncoded.length)
        for (index in xorEncoded.length - 1 downTo 1) {
            state = (state * 97 + 41) % 65519
            swaps[index] = state % (index + 1)
        }
        val unshuffled = xorEncoded.toCharArray()
        for (index in unshuffled.size - 1 downTo 1) {
            val other = swaps[index]
            val temp = unshuffled[index]
            unshuffled[index] = unshuffled[other]
            unshuffled[other] = temp
        }

        // The decoder applies operation '3' (reverse) before shuffling.
        val body = unshuffled.concatToString().reversed()
        val parts = MutableList(12) { "" }
        parts[0] = body
        parts[3] = seedKey
        parts[8] = operations

        assertEquals(expected, FilmMakinesiStreamParser.decodeCloseLoadPayload(parts))
    }

    @Test
    fun resolvesKnownCloseLoadPayloadFromReferenceFixture() {
        val html = """
            <script>
                function nc0(uvx) {
                    var anzgu = uvx.join('');
                    var d25w = "xj1kV8NUjhX1BYHA71jJadM";
                    var om88 = "bvY";
                    return "";
                }
                var payload = ["==f1zOuNp","aPbkyAql0","fN2lM6ffq","6rF/nd13+","acP9LSu8g","utaac/6E8","e+vmPliVe","CWodoyhm/","yJtXiB/6N","yVcH41h5y","4Ct7u453Q","+2Of5ZUpn","7G+nOjjGN","TN+BGg+Rp","v1Eoi5j7u","43W+jk8+y"];
            </script>
        """.trimIndent()

        assertEquals(
            listOf("https://srv9.cdnimages3055.shop/hls/mayday-2026-webdl-trdual-tt28014327mp4-OSxZYB25jjD4.mp4/txt/master.txt"),
            FilmMakinesiStreamParser.extractStreamUrls(
                html,
                "https://closeload.filmmakinesi.to/embed/fixture"
            )
        )
    }

    @Test
    fun ignoresUnrelatedScriptsAndMalformedPackerPayloads() {
        assertEquals(emptyList<String>(), FilmMakinesiJsPacker.unpackAll("var player = { file: '/video.m3u8' };"))
        assertEquals(emptyList<String>(), FilmMakinesiJsPacker.unpackAll("eval(function(p,a,c,k,e,d){return p;}('0',999,1,'x'.split('|'),0,{}))"))
    }

    @Test
    fun decodesCurrentDcArrayPayloadUsingReferenceOperationOrder() {
        val expected = "https://cdn.example.test/movie/master.m3u8"
        // Reverse the resolver's final byte-unmix stage to build a deterministic fixture.
        val preUnmix = buildString(expected.length) {
            expected.forEachIndexed { index, ch ->
                val shift = (399756995L % (index + 5)).toInt()
                append(((ch.code + shift) % 256).toChar())
            }
        }
        val rotate13 = preUnmix.map { ch ->
            when (ch) {
                in 'a'..'z' -> ((ch.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((ch.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                else -> ch
            }
        }.joinToString("")
        val base64 = java.util.Base64.getEncoder()
            .encodeToString(rotate13.toByteArray(Charsets.ISO_8859_1))
        val encodedChunks = base64.reversed().chunked(7).joinToString(",") { "\\"$it\\"" }
        val html = "<script>dc_hello([$encodedChunks]);</script>"

        assertEquals(
            listOf(expected),
            FilmMakinesiStreamParser.extractStreamUrls(
                html,
                "https://closeload.filmmakinesi.to/embed/native-fixture"
            )
        )
    }

}

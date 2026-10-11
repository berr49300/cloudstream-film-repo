/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilmMakinesiPlayerResolverTest {
    @Test
    fun detectsPlayerIdentityFromUrl() {
        assertEquals("CloseLoad", FilmMakinesiPlayerDecoder.playerName("https://closeload.filmmakinesi.to/embed/abc"))
        assertEquals("Rapid", FilmMakinesiPlayerDecoder.playerName("https://rapid.filmmakinesi.to/player/abc"))
        assertEquals("Rapidrame", FilmMakinesiPlayerDecoder.playerName("https://rapid.filmmakinesi.to/rplayer/abc"))
    }

    @Test
    fun extractsVariableUsedByTheSourcesArray() {
        assertEquals(
            "mediaSource",
            FilmMakinesiPlayerDecoder.sourceVariable(
                """jwplayer({sources: [{file: mediaSource, type: "hls"}]});"""
            )
        )
    }

    @Test
    fun ignoresDirectStringAndMalformedSourcesWhenLookingForVariable() {
        assertNull(FilmMakinesiPlayerDecoder.sourceVariable("""jwplayer({sources: [{file: "https://cdn.test/master.m3u8"}]});"""))
        assertNull(FilmMakinesiPlayerDecoder.sourceVariable("var unrelated = true;"))
    }
    @Test
    fun decodesCurrentDcHelloArrayStreamFixture() {
        val expected = "https://srv9.cdnimages3055.shop/hls/mayday-2026-webdl-trdual-tt28014327mp4-OSxZYB25jjD4.mp4/txt/master.txt"
        val preUnmix = buildString(expected.length) {
            expected.forEachIndexed { index, char ->
                val shift = (399756995L % (index + 5)).toInt()
                append(((char.code + shift) % 256).toChar())
            }
        }
        val rotate13 = preUnmix.map { char ->
            when (char) {
                in 'a'..'z' -> ((char.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((char.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                else -> char
            }
        }.joinToString("")
        val base64 = java.util.Base64.getEncoder()
            .encodeToString(rotate13.toByteArray(Charsets.ISO_8859_1))
        val chunks = base64.reversed().chunked(7).joinToString(",") { "\"$it\"" }
        val html = "<script>dc_hello([$chunks]);</script>"

        assertEquals(listOf(expected), FilmMakinesiPlayerDecoder.extractDcStreams(html))
    }
}

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
        val html = """<script>dc_hello(["==f1zOuNp","aPbkyAql0","fN2lM6ffq","6rF/nd13+","acP9LSu8g","utaac/6E8","e+vmPliVe","CWodoyhm/","yJtXiB/6N","yVcH41h5y","4Ct7u453Q","+2Of5ZUpn","7G+nOjjGN","TN+BGg+Rp","v1Eoi5j7u","43W+jk8+y"]);</script>"""
        assertEquals(
            listOf("https://srv9.cdnimages3055.shop/hls/mayday-2026-webdl-trdual-tt28014327mp4-OSxZYB25jjD4.mp4/txt/master.txt"),
            FilmMakinesiPlayerDecoder.extractDcStreams(html)
        )
    }

}

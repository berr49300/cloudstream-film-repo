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
    fun ignoresUnrelatedScriptsAndMalformedPackerPayloads() {
        assertEquals(emptyList<String>(), FilmMakinesiJsPacker.unpackAll("var player = { file: '/video.m3u8' };"))
        assertEquals(emptyList<String>(), FilmMakinesiJsPacker.unpackAll("eval(function(p,a,c,k,e,d){return p;}('0',999,1,'x'.split('|'),0,{}))"))
    }
}

/*
 * SPDX-License-Identifier: MIT
 */
package com.kra.cloudstream.filmmakinesi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilmMakinesiPlayerResolverTest {
    @Test
    fun detectsPlayerIdentityFromUrl() {
        assertEquals("CloseLoad", FilmMakinesiPlayerResolver.playerName("https://closeload.filmmakinesi.to/embed/abc"))
        assertEquals("Rapid", FilmMakinesiPlayerResolver.playerName("https://rapid.filmmakinesi.to/player/abc"))
        assertEquals("Rapidrame", FilmMakinesiPlayerResolver.playerName("https://rapid.filmmakinesi.to/rplayer/abc"))
    }

    @Test
    fun extractsVariableUsedByTheSourcesArray() {
        assertEquals(
            "mediaSource",
            FilmMakinesiPlayerResolver.sourceVariable(
                """jwplayer({sources: [{file: mediaSource, type: "hls"}]});"""
            )
        )
    }

    @Test
    fun ignoresDirectStringAndMalformedSourcesWhenLookingForVariable() {
        assertNull(FilmMakinesiPlayerResolver.sourceVariable("""jwplayer({sources: [{file: "https://cdn.test/master.m3u8"}]});"""))
        assertNull(FilmMakinesiPlayerResolver.sourceVariable("var unrelated = true;"))
    }
}

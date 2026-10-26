package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixPichiveParserTest {
    @Test
    fun acceptsRotatingIframeHostsWithPlayerTokenButRejectsUnrelatedUrls() {
        assertTrue(SelcukFlixPichiveParser.canHandle("https://four.pichive.online/iframe.php?v=abcd1234"))
        assertTrue(SelcukFlixPichiveParser.canHandle("https://player.playru.me/iframe.php?v=abcd1234"))
        assertTrue(SelcukFlixPichiveParser.canHandle("https://edge-stream.example/iframe.php?x=1&v=token_a%2Fb"))
        assertFalse(SelcukFlixPichiveParser.canHandle("https://unrelated.example/watch.php?v=abcd1234"))
        assertFalse(SelcukFlixPichiveParser.canHandle("https://unrelated.example/iframe.php"))
        assertFalse(SelcukFlixPichiveParser.canHandle("ftp://edge-stream.example/iframe.php?v=abcd1234"))
    }

    @Test
    fun readsPrimaryAndTurkishDubTokens() {
        val html = """<script>openPlayer('primary_token_123456', true); players.push('primary_token_123456', 'dubbed_token_123456', 'Türkçe Dublaj');</script>"""
        val tokens = SelcukFlixPichiveParser.playerTokens(html)
        assertEquals(2, tokens.size)
        assertEquals("primary_token_123456", tokens[0].value)
        assertEquals("dubbed_token_123456", tokens[1].value)
        assertEquals("Türkçe Dublaj", tokens[1].label)
    }

    @Test
    fun keepsRawPhpEndpointAlongsideMasterPlaylistHypothesis() {
        val root = ObjectMapper().readTree(
            """{"state":true,"playlist":[{"title":"Türkçe Dublaj","sources":[{"file":"https://four.pichive.online/media/m.php?key=fixture","label":"720P"}]}]}"""
        )
        val files = SelcukFlixPichiveParser.files(root, "https://four.pichive.online/source2.php?v=fixture")
        assertEquals(1, files.size)
        assertEquals("Türkçe Dublaj", files.single().track)
        assertEquals("720P", files.single().quality)

        val candidates = SelcukFlixPichiveParser.mediaCandidates(files.single().url, "https://four.pichive.online/source2.php?v=fixture")
        assertEquals(2, candidates.size)
        assertEquals("https://four.pichive.online/media/m.php?key=fixture", candidates[0].url)
        assertEquals("Raw PHP fallback", candidates[0].track)
        assertEquals("https://four.pichive.online/media/master.m3u8?key=fixture", candidates[1].url)
        assertEquals("Master playlist hypothesis", candidates[1].track)
    }

    @Test
    fun preservesAlreadyEncodedSignatureQueryWhenBuildingMasterPlaylist() {
        val candidates = SelcukFlixPichiveParser.mediaCandidates(
            "https://four.pichive.online/media/m.php?key=a%2Fb%3D&token=x%2By%3D",
            "https://four.pichive.online/source2.php?v=fixture"
        )

        assertEquals(2, candidates.size)
        assertEquals(
            "https://four.pichive.online/media/m.php?key=a%2Fb%3D&token=x%2By%3D",
            candidates[0].url
        )
        assertEquals(
            "https://four.pichive.online/media/master.m3u8?key=a%2Fb%3D&token=x%2By%3D",
            candidates[1].url
        )
    }

    @Test
    fun doesNotGuessMasterPlaylistForUnrelatedPhpEndpoints() {
        assertTrue(
            SelcukFlixPichiveParser.mediaCandidates(
                "https://four.pichive.online/media/other.php?key=fixture",
                "https://four.pichive.online/source2.php?v=fixture"
            ).isEmpty()
        )
    }

    @Test
    fun encodesSourceApiTokenExactlyOnce() {
        assertEquals(
            "https://four.pichive.online/source2.php?v=a%2Fb%2Bc%3D%3D",
            SelcukFlixPichiveParser.buildSourceApiUrl(
                "https://four.pichive.online/iframe.php?v=outer",
                "a/b+c=="
            )
        )
    }

    @Test
    fun findsFileEvenWhenPlaylistEnvelopeIsAbsent() {
        val root = ObjectMapper().readTree(
            """{"file":"https://four.pichive.online/media/m.php?key=fixture"}"""
        )
        val files = SelcukFlixPichiveParser.files(root, "https://four.pichive.online/source2.php?v=fixture")
        assertEquals(1, files.size)
        assertEquals("https://four.pichive.online/media/m.php?key=fixture", files.single().url)
    }
}

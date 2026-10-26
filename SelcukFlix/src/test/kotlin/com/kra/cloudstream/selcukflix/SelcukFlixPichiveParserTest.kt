package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixPichiveParserTest {
    @Test
    fun recognizesObservedPichiveIframeHostsOnly() {
        assertTrue(SelcukFlixPichiveParser.canHandle("https://four.pichive.online/iframe.php?v=abcd1234"))
        assertTrue(SelcukFlixPichiveParser.canHandle("https://player.playru.me/iframe.php?v=abcd1234"))
        assertFalse(SelcukFlixPichiveParser.canHandle("https://unrelated.example/iframe.php?v=abcd1234"))
    }

    @Test
    fun readsPrimaryAndTurkishDubTokens() {
        val html = """<script>openPlayer('primary_token_123456', true); players.push('dubbed_token_123456', 'Türkçe Dublaj');</script>"""
        val tokens = SelcukFlixPichiveParser.playerTokens(html)
        assertEquals(2, tokens.size)
        assertEquals("primary_token_123456", tokens[0].value)
        assertEquals("dubbed_token_123456", tokens[1].value)
        assertEquals("Türkçe Dublaj", tokens[1].label)
    }

    @Test
    fun parsesPlaylistFilesAndBuildsMasterPlaylistFallback() {
        val root = ObjectMapper().readTree(
            """{"state":true,"playlist":[{"title":"Türkçe Dublaj","sources":[{"file":"https://four.pichive.online/media/m.php?key=fixture","label":"720P"}]}]}"""
        )
        val files = SelcukFlixPichiveParser.files(root, "https://four.pichive.online/source2.php?v=fixture")
        assertEquals(1, files.size)
        assertEquals("Türkçe Dublaj", files.single().track)
        assertEquals("720P", files.single().quality)

        val candidates = SelcukFlixPichiveParser.mediaCandidates(files.single().url, "https://four.pichive.online/source2.php?v=fixture")
        assertTrue(candidates.any { it.url == "https://four.pichive.online/media/master.m3u8?key=fixture" })
        assertTrue(candidates.any { it.url == "https://four.pichive.online/media/m.php?key=fixture" })
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

package com.kra.cloudstream.selcukflix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixContentXSubtitleParserTest {
    @Test
    fun parsesNormalJsonTrackAndResolvesAgainstIframeUrl() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """player={subtitles:[{"file":"../subs/tr.vtt","label":"Türkçe"}]}""",
            "https://player.example/embed/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.vtt", result.single().url)
    }

    @Test
    fun parsesEscapedQuotesSlashesAndUnicodeLabels() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """player={subtitles:[{\"file\":\"\\/subs\\/tr.vtt\",\"label\":\"T\\u00fcrk\\u00e7e\"}]}""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.vtt", result.single().url)
    }

    @Test
    fun supportsLabelBeforeFileAndDeduplicatesUrls() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """{"label":"English","file":"https://cdn.example/en.vtt"} {"file":"https://cdn.example/en.vtt","label":"English duplicate"}""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("English", result.single().label)
    }

    @Test
    fun parsesHtmlTrackAndLanguageAttributes() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """<video><track kind="subtitles" src="../subs/tr.srt" srclang="tr" label="Türkçe"></video>""",
            "https://player.example/embed/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.srt", result.single().url)
    }

    @Test
    fun parsesSrcAndSrclangPlayerConfig() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """{ "src": "/subs/en.vtt", "srclang": "English" }""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("English", result.single().label)
        assertEquals("https://player.example/subs/en.vtt", result.single().url)
    }

    @Test
    fun rejectsNonHttpSubtitleUrls() {
        val result = SelcukFlixContentXSubtitleParser.parse(
            """{"file":"javascript:alert(1)","label":"bad"} {"file":"data:text/plain,hi","label":"bad2"}""",
            "https://player.example/iframe.php?v=123"
        )

        assertTrue(result.isEmpty())
    }
}


class SelcukFlixContentXSourceParserTest {
    @Test
    fun recognizesContentXIframeAndAllPlayerTokens() {
        assertTrue(SelcukFlixContentXSourceParser.canHandle("https://four.pichive.online/iframe.php?v=sample"))
        assertTrue(!SelcukFlixContentXSourceParser.canHandle("https://four.pichive.online/embed/sample"))

        val page = """window.openPlayer('sub_token_123456789', true); data = ,\"dub_token_987654321\",\"Türkçe Dublaj\""""
        val tokens = SelcukFlixContentXSourceParser.playerTokens(page)
        assertEquals(2, tokens.size)
        assertEquals("sub_token_123456789", tokens[0].token)
        assertEquals("Altyazılı / Orijinal", tokens[0].label)
        assertEquals("dub_token_987654321", tokens[1].token)
        assertEquals("Türkçe Dublaj", tokens[1].label)
    }

    @Test
    fun extractsAllMediaFilesFromSourceApiAndIgnoresSubtitleFiles() {
        val json = """{"playlist":[{"file":"https:\/\/media.example\/master.m3u8","label":"HD"},{"file":"https:\/\/media.example\/tr.vtt","label":"Türkçe"}]}"""
        val urls = SelcukFlixContentXSourceParser.mediaFiles(json, "https://player.example/source2.php?v=sample")
        assertEquals(listOf("https://media.example/master.m3u8"), urls)
    }
}

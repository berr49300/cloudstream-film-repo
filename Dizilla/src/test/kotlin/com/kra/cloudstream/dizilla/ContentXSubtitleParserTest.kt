package com.kra.cloudstream.dizilla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentXSubtitleParserTest {
    @Test
    fun parsesNormalJsonTrackAndResolvesAgainstIframeUrl() {
        val result = ContentXSubtitleParser.parse(
            """player={subtitles:[{"file":"../subs/tr.vtt","label":"Türkçe"}]}""",
            "https://player.example/embed/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.vtt", result.single().url)
    }

    @Test
    fun parsesEscapedQuotesSlashesAndUnicodeLabels() {
        val result = ContentXSubtitleParser.parse(
            """player={subtitles:[{\"file\":\"\\/subs\\/tr.vtt\",\"label\":\"T\\u00fcrk\\u00e7e\"}]}""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.vtt", result.single().url)
    }

    @Test
    fun supportsLabelBeforeFileAndDeduplicatesUrls() {
        val result = ContentXSubtitleParser.parse(
            """{"label":"English","file":"https://cdn.example/en.vtt"} {"file":"https://cdn.example/en.vtt","label":"English duplicate"}""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("English", result.single().label)
    }

    @Test
    fun parsesHtmlTrackAndLanguageAttributes() {
        val result = ContentXSubtitleParser.parse(
            """<video><track kind="subtitles" src="../subs/tr.srt" srclang="tr" label="Türkçe"></video>""",
            "https://player.example/embed/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("Türkçe", result.single().label)
        assertEquals("https://player.example/subs/tr.srt", result.single().url)
    }

    @Test
    fun parsesSrcAndSrclangPlayerConfig() {
        val result = ContentXSubtitleParser.parse(
            """{ "src": "/subs/en.vtt", "srclang": "English" }""",
            "https://player.example/iframe.php?v=123"
        )

        assertEquals(1, result.size)
        assertEquals("English", result.single().label)
        assertEquals("https://player.example/subs/en.vtt", result.single().url)
    }

    @Test
    fun rejectsNonHttpSubtitleUrls() {
        val result = ContentXSubtitleParser.parse(
            """{"file":"javascript:alert(1)","label":"bad"} {"file":"data:text/plain,hi","label":"bad2"}""",
            "https://player.example/iframe.php?v=123"
        )

        assertTrue(result.isEmpty())
    }
}

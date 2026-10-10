package com.kra.cloudstream.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCandidateParserTest {
    @Test
    fun extractsProtocolRelativeIframe() {
        assertEquals(
            listOf("https://pichive.example/iframe.php?v=abc"),
            SourceCandidateParser.extract(
                """<iframe src="//pichive.example/iframe.php?v=abc"></iframe>""",
                "https://dizilla.now/"
            )
        )
    }

    @Test
    fun extractsLazyAndRelativeIframeAttributes() {
        assertEquals(
            listOf("https://dizilla.now/embed/episode-1"),
            SourceCandidateParser.extract(
                """<iframe data-src="/embed/episode-1"></iframe>""",
                "https://dizilla.now/"
            )
        )
    }

    @Test
    fun acceptsDirectMediaUrl() {
        assertEquals(
            listOf("https://cdn.example/master.m3u8?token=abc"),
            SourceCandidateParser.extract(
                "https://cdn.example/master.m3u8?token=abc",
                "https://dizilla.now/"
            )
        )
    }

    @Test
    fun extractsUrlFromEscapedJson() {
        val content = """{\"url\":\"https:\/\/cdn.example/master.m3u8\"}"""
        assertEquals(
            listOf("https://cdn.example/master.m3u8"),
            SourceCandidateParser.extract(content, "https://dizilla.now/")
        )
    }

    @Test
    fun rejectsJavascriptAndNonHttpSchemes() {
        val result = SourceCandidateParser.extract(
            """<iframe src="javascript:alert(1)"></iframe><source src="data:video/mp4;base64,AA"/>""",
            "https://dizilla.now/"
        )
        assertTrue(result.isEmpty())
    }
}

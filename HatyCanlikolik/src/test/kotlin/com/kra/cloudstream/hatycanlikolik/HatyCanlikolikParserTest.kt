package com.kra.cloudstream.hatycanlikolik
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class HatyCanlikolikParserTest {
    @Test fun parsesChannelAndMatchLinksOnlyFromCanlikolikHost() {
        val doc = Jsoup.parse("""
            <a href="/kanal/trt-1" class="channel"><img alt="TRT 1"></a>
            <a href="/canli-mac-izle/fenerbahce-galatasaray">Fenerbahçe - Galatasaray</a>
            <a href="https://evil.example/mac/hidden">Spor fake</a>
            <a href="#">beIN Sports</a>
        """.trimIndent(), "https://canlikolik11.live/")
        val entries = CanlikolikHtmlParser.parseCatalog(doc)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.title.contains("TRT 1") })
        assertTrue(entries.any { it.title.contains("Fenerbahçe") })
        assertTrue(entries.all { CanlikolikHtmlParser.isCanlikolikUrl(it.url) })
    }
}
package com.kra.cloudstream.hatycanlitribun
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class HatyCanliTribunParserTest {
    @Test fun parsesEventLinksAndRejectsForeignOrPlaceholderLinks() {
        val doc = Jsoup.parse("""
            <a href="/canli-mac-izle/fenerbahce-galatasaray">Fenerbahçe - Galatasaray</a>
            <a href="/mac-izle/basketbol">Basketbol canlı yayın</a>
            <a href="https://evil.example/mac/hidden">Canlı maç fake</a>
            <a href="javascript:void(0)">Canlı maç placeholder</a>
        """.trimIndent(), "https://www.canlitribun938.top/canli-mac-izle/")
        val entries = CanliTribunHtmlParser.parseCatalog(doc)
        assertEquals(2, entries.size)
        assertTrue(entries.all { CanliTribunHtmlParser.isSiteUrl(it.url) })
    }
}
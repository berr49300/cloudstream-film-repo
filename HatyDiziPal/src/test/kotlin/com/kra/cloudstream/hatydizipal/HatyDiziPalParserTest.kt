package com.kra.cloudstream.hatydizipal
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class HatyDiziPalParserTest {
    @Test fun parsesOnlySupportedSameHostCatalogLinks() {
        val base = "https://dizipalorjinal14.com/"
        val doc = Jsoup.parse("""
            <a href="/dizi/reacher" title="Reacher"><img src="/poster.jpg"></a>
            <a href="/film/dune-2"><span class="title">Dune: Part Two</span></a>
            <a href="https://untrusted.example/dizi/fake">Fake</a>
            <a href="/kategori/diziler">Diziler</a>
        """.trimIndent(), base)
        val entries = doc.select("a").mapNotNull { DiziPalHtmlParser.parseCatalogElement(it, base) }
        assertEquals(2, entries.size)
        assertTrue(entries[0].isSeries)
        assertEquals("Reacher", entries[0].title)
        assertEquals("https://dizipalorjinal14.com/dizi/reacher", entries[0].url)
        assertEquals("Dune: Part Two", entries[1].title)
    }
    @Test fun mapsEpisodesAndRejectsForeignHosts() {
        val base = "https://dizipalorjinal14.com/dizi/silo"
        val doc = Jsoup.parse("""
            <h1>Silo</h1>
            <a href="/bolum/silo-1-sezon-1-bolum">1. Sezon 1. Bölüm</a>
            <a href="/bolum/silo-1-sezon-2-bolum">1. Sezon 2. Bölüm</a>
            <a href="https://untrusted.example/bolum/silo-1-sezon-3-bolum">No</a>
        """.trimIndent(), base)
        val entries = DiziPalHtmlParser.parseEpisodeEntries(doc, base)
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].season)
        assertEquals(1, entries[0].episode)
        assertEquals("https://dizipalorjinal14.com/bolum/silo-1-sezon-1-bolum", entries[0].url)
        assertFalse(DiziPalHtmlParser.isSiteUrl("https://evil.example/dizi/silo"))
    }
}
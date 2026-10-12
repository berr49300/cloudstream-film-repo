package com.kra.cloudstream.hatydizipal

import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import org.jsoup.Jsoup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HatyDiziPalParserTest {
    private val api = HatyDiziPal()

    @Test
    fun parsesOnlySupportedSameHostCatalogLinks() {
        val doc = Jsoup.parse(
            """
            <a href="/dizi/reacher" title="Reacher"><img src="/poster.jpg"></a>
            <a href="/film/dune-2"><span class="title">Dune: Part Two</span></a>
            <a href="https://untrusted.example/dizi/fake">Fake</a>
            <a href="/kategori/diziler">Diziler</a>
            """.trimIndent(),
            api.mainUrl
        )
        val items = doc.select("a").mapNotNull(api::parseSearchElement)
        assertEquals(2, items.size)
        assertTrue(items[0] is TvSeriesSearchResponse)
        assertEquals("Reacher", items[0].name)
        assertEquals("https://dizipalorjinal14.com/dizi/reacher", items[0].url)
        assertEquals("Dune: Part Two", items[1].name)
    }

    @Test
    fun mapsEpisodeSlugSeasonAndNumberWithoutFabricatingEpisodes() = runBlocking {
        val base = api.mainUrl
        val doc = Jsoup.parse(
            """
            <html><head><meta property="og:image" content="/poster.jpg"></head><body>
              <h1>Silo</h1><div class="summary">Test synopsis</div>
              <a href="/bolum/silo-1-sezon-1-bolum">1. Sezon 1. Bölüm</a>
              <a href="/bolum/silo-1-sezon-2-bolum">1. Sezon 2. Bölüm</a>
              <a href="https://untrusted.example/bolum/silo-1-sezon-3-bolum">No</a>
            </body></html>
            """.trimIndent(),
            base
        )
        val result = api.parseLoadMetadata(doc, "$base/dizi/silo") as? TvSeriesLoadResponse
        assertNotNull(result)
        assertEquals("Silo", result?.name)
        assertEquals(2, result?.episodes?.size)
        assertEquals(1, result?.episodes?.get(0)?.season)
        assertEquals(1, result?.episodes?.get(0)?.episode)
        assertEquals("https://dizipalorjinal14.com/bolum/silo-1-sezon-1-bolum", result?.episodes?.get(0)?.data)
    }

    @Test
    fun rejectsUnsupportedOrForeignDetailUrls() = runBlocking {
        val base = api.mainUrl
        val doc = Jsoup.parse("<h1>Not a title</h1>", base)
        assertNull(api.parseLoadMetadata(doc, "https://untrusted.example/dizi/item"))
        assertNull(api.parseLoadMetadata(doc, "$base/kategori/diziler"))
    }
}
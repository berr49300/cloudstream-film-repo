package com.kra.cloudstream.hatycanlikolik
import com.lagradost.cloudstream3.LiveSearchResponse
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
class HatyCanlikolikParserTest {
    @Test fun parsesChannelAndMatchLinksOnlyFromCanlikolikHost() {
        val provider = HatyCanlikolik()
        val doc = Jsoup.parse("""
            <a href="/kanal/trt-1" class="channel"><img alt="TRT 1"></a>
            <a href="/canli-mac-izle/fenerbahce-galatasaray">Fenerbahçe - Galatasaray</a>
            <a href="https://evil.example/mac/hidden">Spor fake</a>
            <a href="#">beIN Sports</a>
        """.trimIndent(), "https://canlikolik11.live/")
        val results = provider.parseCatalog(doc)
        assertEquals(2, results.size)
        assertTrue(results.all { it is LiveSearchResponse })
        assertTrue(results.any { it.name.contains("TRT 1") })
        assertTrue(results.any { it.name.contains("Fenerbahçe") })
    }
}
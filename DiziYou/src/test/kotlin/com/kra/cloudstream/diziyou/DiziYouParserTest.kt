package com.kra.cloudstream.diziyou

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiziYouParserTest {
    @Test fun titleAndPosterCanBeReadFromCard() {
        val doc = Jsoup.parse("<main><article><a href='https://www.diziyou.one/silo/'><img src='/poster.jpg' alt='Silo'><h3>Silo</h3></a></article></main>", "https://www.diziyou.one/")
        val card = doc.selectFirst("main a[href]")
        assertNotNull(card)
        assertEquals("Silo", card!!.selectFirst("h3")!!.text())
        assertEquals("/poster.jpg", card.selectFirst("img")!!.attr("src"))
    }
    @Test fun episodeLabelExposesEpisodeNumber() {
        val match = Regex("(?i)(\\d+)\\.\\s*(?:bölüm|bolum)").find("1. Sezon 2. Bölüm (test)")
        assertEquals("2", match?.groupValues?.getOrNull(1))
    }
}

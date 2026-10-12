package com.kra.cloudstream.diziyou

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiziYouParserTest {
    @Test fun titleAndPosterCanBeReadFromCard() {
        val doc = Jsoup.parse("<main><article><a href='https://www.diziyou.one/silo/'><img src='/poster.jpg' alt='Silo'><h3>Silo</h3></a></article></main>", "https://www.diziyou.one/")
        val card = doc.selectFirst("main a[href]")
        assertEquals("Silo", card!!.selectFirst("h3")!!.text())
        assertEquals("/poster.jpg", card.selectFirst("img")!!.attr("src"))
    }
    @Test fun episodeLabelExposesSeasonAndEpisodeNumbers() {
        assertEquals(2 to 3, parseEpisodeNumbers("2. Sezon 3. Bölüm (Test)"))
    }
    @Test fun episodeSlugExposesSeasonAndEpisodeNumbers() {
        assertEquals(1 to 1, parseEpisodeNumbers("https://www.diziyou.one/sold-out-on-you-1-sezon-1-bolum/"))
    }
    @Test fun playerIdIsFoundFromIframe() {
        val doc = Jsoup.parse("<iframe id='diziyouPlayer' src='https://player.example/player/item_123.html'></iframe>")
        assertEquals("item_123", DiziYou().extractPlayerId(doc))
    }
    @Test fun playerIdIsFoundFromInlineConfig() {
        val doc = Jsoup.parse("<script>var itemId = 'item_456';</script>")
        assertEquals("item_456", DiziYou().extractPlayerId(doc))
    }
    @Test fun playerCandidatesIncludeIframeAndVideoSource() {
        val doc = Jsoup.parse("<iframe src='https://embed.example/watch/1'></iframe><video><source src='https://cdn.example/video.m3u8'></video>")
        assertTrue(DiziYou().getPlayerCandidates(doc).size >= 2)
    }
}

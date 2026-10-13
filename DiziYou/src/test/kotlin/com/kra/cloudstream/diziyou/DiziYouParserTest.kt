package com.kra.cloudstream.diziyou

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
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

    @Test fun playerSourceParserFindsPlainAndEscapedHlsUrls() {
        val html = """<script>
            const first = "https://storage.diziyou.one/episodes/123/play.m3u8";
            const second = "https:\/\/storage.diziyou.one/episodes/124/play.m3u8";
        </script>"""
        val doc = Jsoup.parse(html, "https://www.diziyou.one/")
        val urls = DiziYou().sourceUrls(doc).filter { it.contains(".m3u8", ignoreCase = true) }.toSet()
        assertEquals(
            setOf(
                "https://storage.diziyou.one/episodes/123/play.m3u8",
                "https://storage.diziyou.one/episodes/124/play.m3u8"
            ),
            urls
        )
    }

    @Test fun playerIframeUrlCanBeResolvedFromDataSrc() {
        val doc = Jsoup.parse(
            """<iframe id="diziyouPlayer" data-src="/player/91303.html"></iframe>""",
            "https://www.diziyou.one/"
        )
        val frame = doc.selectFirst("iframe#diziyouPlayer")!!
        assertEquals("/player/91303.html", frame.attr("data-src"))
    }

}

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
        val urls = parseDiziYouSourceUrls(doc).filter { it.contains(".m3u8", ignoreCase = true) }.toSet()
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
        assertEquals(
            listOf("https://www.diziyou.one/player/91303.html"),
            parseDiziYouSourceUrls(doc)
        )
    }

    @Test fun episodeSlugMapsBackToSeriesUrl() {
        assertEquals(
            "https://www.diziyou.one/sold-out-on-you/",
            seriesUrlFromEpisode("https://www.diziyou.one/sold-out-on-you-1-sezon-1-bolum/")
        )
    }

    @Test fun episodeUrlWithNonWwwHostMapsBackToSeries() {
        assertEquals(
            "https://www.diziyou.one/youth/",
            seriesUrlFromEpisode("https://diziyou.one/youth-1-sezon-5-bolum/?ref=home")
        )
    }

    @Test fun episodeCardTitleIsDerivedFromSeriesSlugNotArtworkCaption() {
        assertEquals(
            "Youth",
            seriesTitleFromEpisodeUrl("https://www.diziyou.one/youth-1-sezon-5-bolum/")
        )
        assertEquals(
            "Sold Out On You",
            seriesTitleFromEpisodeUrl("https://www.diziyou.one/sold-out-on-you-1-sezon-1-bolum/")
        )
    }

    @Test fun actualPlayerSourceElementIsExtracted() {
        val doc = Jsoup.parse(
            """<video><source id="diziyouSource" src="/episodes/91303/720p.m3u8"></video>""",
            "https://www.diziyou.one/player/91303.html"
        )
        assertEquals(
            listOf("https://www.diziyou.one/episodes/91303/720p.m3u8"),
            parseDiziYouSourceUrls(doc)
        )
    }

    @Test fun unrelatedUrlIsNotMappedToSeries() {
        assertEquals(null, seriesUrlFromEpisode("https://example.com/show-1-sezon-1-bolum/"))
    }


    @Test fun fileOnlyEpisodeNamesBecomeCleanSeasonAndEpisodeLabels() {
        assertEquals(
            "1. Sezon 1. Bölüm",
            cleanDiziYouEpisodeName("1. (eps1.0_hellofriend.mov)", 1, 1)
        )
    }

    @Test fun readableEpisodeTitleIsPreservedWithoutTheFilename() {
        assertEquals(
            "1. Sezon 1. Bölüm — Hello Friend",
            cleanDiziYouEpisodeName("1. Hello Friend (eps1.0_hellofriend.mov)", 1, 1)
        )
    }

    @Test fun selectedNativeSourceWinsOverOtherQualityCandidates() {
        val doc = Jsoup.parse(
            """<video>
                <source id="diziyouSource" src="/episodes/91303/720p.m3u8">
                <source src="/episodes/91303/1080p.m3u8">
            </video>""",
            "https://www.diziyou.one/player/91303.html"
        )
        assertEquals(
            listOf("https://www.diziyou.one/episodes/91303/720p.m3u8"),
            parseDiziYouMediaUrls(doc)
        )
    }

    @Test fun playerScriptFallbackEmitsOnlyOneCandidate() {
        val doc = Jsoup.parse(
            """<script>
                const a = "https://storage.diziyou.one/episodes/91303/720p.m3u8";
                const b = "https://storage.diziyou.one/episodes/91303/1080p.m3u8";
            </script>""",
            "https://www.diziyou.one/player/91303.html"
        )
        assertEquals(1, parseDiziYouMediaUrls(doc).size)
    }

    @Test fun originalQualitySelectionUsesTheHlsMasterPlaylist() {
        assertEquals(
            "https://storage.diziyou.one/episodes/91303/play.m3u8",
            diziYouMasterPlaylistUrl("91303")
        )
    }

    @Test fun turkishDubGetsItsOwnMasterPlaylist() {
        assertEquals(
            "https://storage.diziyou.one/episodes/91303_tr/play.m3u8",
            diziYouMasterPlaylistUrl("91303", dubbed = true)
        )
    }

    @Test fun languageOptionsRequireActualOptionElements() {
        val doc = Jsoup.parse(
            """<script>const options = ["turkceAltyazili", "turkceDublaj"];</script>
               <span class="diziyouOption" id="turkceAltyazili"></span>"""
        )
        assertEquals(true, hasDiziYouOption(doc, "turkceAltyazili"))
        assertEquals(false, hasDiziYouOption(doc, "turkceDublaj"))
    }

    @Test fun invalidPlayerIdsCannotEnterStorageUrls() {
        var thrown = false
        try {
            diziYouMasterPlaylistUrl("../123")
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }

    @Test fun searchRequiresRelevantTitleOrSlugTerms() {
        assertEquals(
            true,
            matchesDiziYouSearchQuery(
                "The Queen's Gambit",
                "https://www.diziyou.one/the-queens-gambit/",
                "gambit"
            )
        )
        assertEquals(
            false,
            matchesDiziYouSearchQuery(
                "The Uncanny Counter",
                "https://www.diziyou.one/the-uncanny-counter/",
                "gambit"
            )
        )
    }

    @Test fun searchMatchingIgnoresTurkishDiacritics() {
        assertEquals(
            true,
            matchesDiziYouSearchQuery(
                "100 Yıl Yaşamak: Mavi Bölgelerin Sırları",
                "https://www.diziyou.one/100-yil-yasamak/",
                "mavi bolgelerin"
            )
        )
    }
}

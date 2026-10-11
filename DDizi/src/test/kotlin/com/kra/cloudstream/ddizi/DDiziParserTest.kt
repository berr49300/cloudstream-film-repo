package com.kra.cloudstream.ddizi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DDiziParserTest {
    @Test
    fun recognizesObservedSeriesAndEpisodeRoutes() {
        assertTrue(DDiziParser.isSeriesUrl("https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"))
        assertTrue(DDiziParser.isEpisodeUrl("https://www.ddizi.tel/izle/38955/kanit-100-bolum-final.htm"))
        assertFalse(DDiziParser.isEpisodeUrl("https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"))
    }

    @Test
    fun extractsEpisodeNumberFromTurkishTitle() {
        assertEquals(100, DDiziParser.episodeNumber("Kanıt 100. Bölüm Final izle"))
    }

    @Test
    fun cleansEpisodeSuffixFromTitle() {
        assertEquals("Kanıt", DDiziParser.cleanTitle("Kanıt 100. Bölüm Final izle"))
    }

    @Test
    fun cleansLatestEpisodeLandingTitleToSeriesTitle() {
        assertEquals("Sevdiğim Sensin", DDiziParser.cleanTitle("Sevdiğim Sensin Son Bölüm izle Full"))
    }

    @Test
    fun onlyKeepsEpisodesWhoseLabelMatchesTheSeries() {
        val seriesTitle = "Sevdiğim Sensin Son Bölüm izle Full"
        assertTrue(DDiziParser.belongsToSeriesEpisode(seriesTitle, "Sevdiğim Sensin 20.Bölüm"))
        assertFalse(DDiziParser.belongsToSeriesEpisode(seriesTitle, "Ömür Usta 3.Bölüm izle"))
        assertFalse(DDiziParser.belongsToSeriesEpisode(seriesTitle, "Mercan Köşk 6.Bölüm izle"))
    }

    @Test
    fun resolvesDdiziYoutubeRelayToYoutubeWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=BC30qtjYt3E",
            DDiziParser.youtubeWatchUrlIfEmbed("https://www.ddizi.re/player/youtube.php?id=BC30qtjYt3E")
        )
        assertEquals(null, DDiziParser.youtubeWatchUrlIfEmbed("https://example.com/player/youtube.php?id=BC30qtjYt3E"))
    }
}

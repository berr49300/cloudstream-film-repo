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
        assertTrue(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/yabanci-dizi-izle"))
        assertTrue(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/eski.diziler"))
        assertFalse(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/yeni-eklenenler8"))
    }

    @Test
    fun extractsEpisodeNumberFromTurkishTitle() {
        assertEquals(100, DDiziParser.episodeNumber("Kanıt 100. Bölüm Final izle"))
        assertEquals(8, DDiziParser.episodeNumber("Dark 3.Sezon 8.Bölüm"))
    }

    @Test
    fun cleansEpisodeSeasonAndSeoSuffixesFromTitle() {
        assertEquals("Kanıt", DDiziParser.cleanTitle("Kanıt 100. Bölüm Final izle"))
        assertEquals("Dark", DDiziParser.cleanTitle("Dark 3.Sezon 8.Bölüm izle"))
        assertEquals("Dark", DDiziParser.cleanTitle("Dark izle Türkçe Dublaj Tüm Bölümler HD"))
        assertEquals("Sevdiğim Sensin", DDiziParser.cleanTitle("Sevdiğim Sensin Son Bölüm izle Full"))
        assertEquals("Teşkilat", DDiziParser.cleanTitle("Teşkilat 189 Son Bölüm izle"))
        assertEquals("Daha 17", DDiziParser.cleanTitle("Daha 17 20.Bölüm izle Full"))
    }

    @Test
    fun normalizesTurkishTitleCharactersForSearchMatching() {
        assertEquals("sevdigim sensin", DDiziParser.normalizeTitle("Sevdiğim Sensin"))
        assertEquals("dark", DDiziParser.normalizeTitle("DARK"))
    }

    @Test
    fun onlyKeepsEpisodesWhoseUrlBelongsToTheSeries() {
        val seriesTitle = "Sevdiğim Sensin Son Bölüm izle Full"
        assertTrue(DDiziParser.belongsToSeriesEpisode(
            seriesTitle,
            "Sevdiğim Sensin 20.Bölüm",
            "https://www.ddizi.tel/izle/1/sevdigim-sensin-20-bolum-izle.htm"
        ))
        assertFalse(DDiziParser.belongsToSeriesEpisode(
            seriesTitle,
            "Sevdiğim Sensin 37.Bölüm",
            "https://www.ddizi.tel/izle/2/guller-ve-gunahlar-37-bolum-izle.htm"
        ))
        assertFalse(DDiziParser.belongsToSeriesEpisode(
            seriesTitle,
            "3. Ömür Usta 3.Bölüm izle",
            "https://www.ddizi.tel/izle/3/omur-usta-3-bolum-izle.htm"
        ))
        assertTrue(DDiziParser.belongsToSeriesEpisode(
            "Dark izle Türkçe Dublaj Tüm Bölümler HD",
            "Dark 3.Sezon 8.Bölüm",
            "https://www.ddizi.tel/izle/72226/dark-3-sezon-8-bolum-izle.htm"
        ))
    }

    @Test
    fun findsCorrectSeriesPageFromEpisodeTitle() {
        assertTrue(DDiziParser.seriesUrlMatchesTitle(
            "Daha 17 1.Bölüm izle",
            "https://www.ddizi.tel/diziler/2167/daha-17-son-bolum-izle"
        ))
        assertTrue(DDiziParser.seriesUrlMatchesTitle(
            "Dark 3.Sezon 8.Bölüm",
            "https://www.ddizi.tel/diziler/1815/dark-hd"
        ))
        assertFalse(DDiziParser.seriesUrlMatchesTitle(
            "Daha 17 1.Bölüm izle",
            "https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"
        ))
    }

    @Test
    fun readsExplicitSeasonNumbersFromEpisodeTitlesAndSlugs() {
        assertEquals(3, DDiziParser.seasonNumber("Dark 3.Sezon 8.Bölüm"))
        assertEquals(2, DDiziParser.seasonNumber("Dark 2 sezon 8 bölüm"))
        assertEquals(null, DDiziParser.seasonNumber("Sevdiğim Sensin 15.Bölüm"))
    }

    @Test
    fun resolvesHistoricalYoutubeRelay() {
        assertEquals(
            "https://www.youtube.com/watch?v=BC30qtjYt3E",
            DDiziParser.youtubeWatchUrlIfEmbed("https://www.ddizi.re/player/youtube.php?id=BC30qtjYt3E")
        )
        assertEquals(null, DDiziParser.youtubeWatchUrlIfEmbed(
            "https://example.com/player/youtube.php?id=BC30qtjYt3E"
        ))
    }

    @Test
    fun resolvesCurrentTelifRelayWithNestedYoutubeUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=qYvM26WeYeg",
            DDiziParser.youtubeWatchUrlIfEmbed(
                "https://www.ddizi.tel/player/telif/index.php?id=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DqYvM26WeYeg"
            )
        )
    }

    @Test
    fun mapsGlobalEpisodeNumbersToSeasonLocalIndicesWithoutMixingSeries() {
        val teskilatFinals = DDiziParser.seasonFinalEpisodeNumbers(
            listOf(
                "Teşkilat 14.Bölüm Sezon Finali",
                "Teşkilat 48.Bölüm Sezon Finali",
                "Teşkilat 80.Bölüm Sezon Finali"
            )
        )
        assertEquals(setOf(14, 48, 80), teskilatFinals)
        assertEquals(1, DDiziParser.seasonForEpisode(14, teskilatFinals))
        assertEquals(2, DDiziParser.seasonForEpisode(15, teskilatFinals))
        assertEquals(2, DDiziParser.seasonForEpisode(48, teskilatFinals))
        assertEquals(3, DDiziParser.seasonForEpisode(49, teskilatFinals))
        assertEquals(3, DDiziParser.seasonForEpisode(80, teskilatFinals))
        assertEquals(4, DDiziParser.seasonForEpisode(81, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(15, teskilatFinals))
        assertEquals(34, DDiziParser.seasonLocalEpisodeNumber(48, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(49, teskilatFinals))
        assertEquals(12, DDiziParser.seasonLocalEpisodeNumber(60, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(81, teskilatFinals))

        val sevFinals = DDiziParser.seasonFinalEpisodeNumbers(
            listOf("Sevdiğim Sensin 15.Bölüm Sezon Finali")
        )
        assertEquals(setOf(15), sevFinals)
        assertEquals(1, DDiziParser.seasonForEpisode(15, sevFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(15, sevFinals))
        assertEquals(null, DDiziParser.seasonForEpisode(null, teskilatFinals))
    }
}

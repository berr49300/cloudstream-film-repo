package com.kra.cloudstream.ddizi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DDiziParserTest {
    @Test
    fun recognizesObservedSeriesAndEpisodeRoutes() {
        assertEquals("2171", DDiziParser.seriesId("https://www.ddizi.tel/diziler/2171/alti-ustu-istanbul"))
        assertEquals("2171", DDiziParser.seriesId("https://www.ddizi.tel/diziler/2171/alti-ustu-istanbul-son-bolum-izle"))
        assertEquals(null, DDiziParser.seriesId("https://www.ddizi.tel/izle/2171/test.htm"))
        assertTrue(DDiziParser.isDdiziHost("https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"))
        assertTrue(DDiziParser.isDdiziHost("https://www.ddizi.im/diziler/2160/sevdigim-sensin-son-bolum-izle"))
        assertFalse(DDiziParser.isDdiziHost("https://example.com/diziler/2160/test"))
        assertTrue(DDiziParser.isSeriesUrl("https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"))
        assertTrue(DDiziParser.isEpisodeUrl("https://www.ddizi.tel/izle/38955/kanit-100-bolum-final.htm"))
        assertFalse(DDiziParser.isEpisodeUrl("https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle"))
        assertTrue(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/yabanci-dizi-izle"))
        assertTrue(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/eski.diziler"))
        assertTrue(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/eski.diziler.izle"))
        assertFalse(DDiziParser.isSeriesCatalogUrl("https://www.ddizi.tel/yeni-eklenenler8"))
        assertEquals(0, DDiziParser.seriesPageNumber("https://www.ddizi.tel/diziler/1856/teskilat/sayfa-0"))
        assertEquals(1, DDiziParser.seriesPageNumber("https://www.ddizi.tel/diziler/1856/teskilat/sayfa-1"))
        assertEquals(null, DDiziParser.seriesPageNumber("https://www.ddizi.tel/diziler/1856/teskilat"))
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
        assertEquals("Teşkilat", DDiziParser.cleanTitle("Teşkilat 189.Bölüm izle"))
        assertEquals("Daha 17", DDiziParser.cleanTitle("Daha 17 Son Bölüm izle"))
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
        assertTrue(DDiziParser.belongsToSeriesEpisode(
            "Daha 17 Son Bölüm izle",
            "Daha 17 20.Bölüm",
            "https://www.ddizi.tel/izle/91910/daha-17-20-bolum-izle-hd2.htm"
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
        assertEquals(
            "https://www.youtube.com/watch?v=BC30qtjYt3E",
            DDiziParser.youtubeWatchUrlIfEmbed("https://www.youtube.com/embed/BC30qtjYt3E")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=BC30qtjYt3E",
            DDiziParser.youtubeWatchUrlIfEmbed("https://youtu.be/BC30qtjYt3E")
        )
    }

    @Test
    fun resolvesCurrentTelifRelayWithSingleAndDoubleEncodedUrls() {
        assertEquals(
            "https://www.youtube.com/watch?v=qYvM26WeYeg",
            DDiziParser.youtubeWatchUrlIfEmbed(
                "https://www.ddizi.tel/player/telif/index.php?id=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DqYvM26WeYeg"
            )
        )
        assertEquals(
            "https://www.youtube.com/watch?v=qYvM26WeYeg",
            DDiziParser.youtubeWatchUrlIfEmbed(
                "https://www.ddizi.tel/player/telif/index.php?id=https%253A%252F%252Fwww.youtube.com%252Fwatch%253Fv%253DqYvM26WeYeg"
            )
        )
    }

    @Test
    fun prefers1080pThen720pFallbackAndAvoidsExcessivelyHighResolution() {
        assertEquals(
            0,
            DDiziParser.preferredYoutubeVariantIndex(
                listOf(
                    "YouTube H264" to 1080,
                    "YouTube VP9" to 720,
                    "YouTube H264" to 720,
                    "YouTube H264" to 480
                )
            )
        )
        assertEquals(
            0,
            DDiziParser.preferredYoutubeVariantIndex(
                listOf("YouTube H264" to 1080, "YouTube VP9" to 720, "YouTube VP9" to 480)
            )
        )
        assertEquals(
            1,
            DDiziParser.preferredYoutubeVariantIndex(
                listOf("YouTube H264" to 2160, "YouTube H264" to 1080)
            )
        )
    }

    @Test
    fun keepsAUsefulYoutubeQualityFallbackAndReadsQualityFromLabel() {
        assertEquals(
            listOf(0, 2),
            DDiziParser.preferredYoutubeVariantIndices(
                listOf(
                    "YouTube H264 1080p" to 1080,
                    "YouTube VP9 720p" to 720,
                    "YouTube H264 720p" to 720,
                    "YouTube H264 480p" to 480
                )
            )
        )
        assertEquals(
            listOf(1, 2),
            DDiziParser.preferredYoutubeVariantIndices(
                listOf(
                    "YouTube H264 2160p" to 2160,
                    "YouTube H264 720p" to 720,
                    "YouTube H264 480p" to 480
                )
            )
        )
        assertEquals(1080, DDiziParser.qualityFromLabel("YouTube H264 1080p"))
        assertEquals(null, DDiziParser.qualityFromLabel("YouTube H264"))
    }

    @Test
    fun prefersOneDefaultAacTrackOverTranslatedOpusTracks() {
        assertEquals(0, DDiziParser.youtubeAudioTrackPriority("https://video.google.test/v?itag=140"))
        assertEquals(1, DDiziParser.youtubeAudioTrackPriority("https://video.google.test/v?itag=251"))
        assertEquals(2, DDiziParser.youtubeAudioTrackPriority("https://video.google.test/v?itag=140&tlang=en"))
        assertEquals(3, DDiziParser.youtubeAudioTrackPriority("https://video.google.test/v?itag=251&tlang=en"))
    }

    @Test
    fun mapsTeskilatEpisodesUsingSeasonFinalsFromFullHistory() {
        // DDizi uses global numbering, with the season finale marker on episodes 14, 48, 79,
        // 111, 145 and 184. A page-0 duplicate must not replace historical pages in the scan.
        val finals = DDiziParser.seasonFinalEpisodeNumbers(
            listOf(
                "Teşkilat 14.Bölüm Sezon Finali",
                "Teşkilat 48.Bölüm Sezon Finali",
                "Teşkilat 79.Bölüm Sezon Finali",
                "Teşkilat 111.Bölüm Sezon Finali",
                "Teşkilat 145.Bölüm Sezon Finali",
                "Teşkilat 184.Bölüm Sezon Finali"
            )
        )
        assertEquals(setOf(14, 48, 79, 111, 145, 184), finals)
        assertEquals(4, DDiziParser.seasonForEpisode(109, finals))
        assertEquals(4, DDiziParser.seasonForEpisode(111, finals))
        assertEquals(5, DDiziParser.seasonForEpisode(112, finals))
        assertEquals(5, DDiziParser.seasonForEpisode(145, finals))
        assertEquals(6, DDiziParser.seasonForEpisode(146, finals))
        assertEquals(7, DDiziParser.seasonForEpisode(185, finals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(109, finals))
        assertEquals(3, DDiziParser.seasonLocalEpisodeNumber(111, finals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(112, finals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(146, finals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(185, finals))
    }

    @Test
    fun mapsGlobalEpisodeNumbersToSeasonLocalIndicesWithoutMixingSeries() {
        // Observed Teşkilat season boundaries: 14, 48, and 79 are season finales.
        val teskilatFinals = DDiziParser.seasonFinalEpisodeNumbers(
            listOf(
                "Teşkilat 14.Bölüm Sezon Finali",
                "Teşkilat 48.Bölüm Sezon Finali",
                "Teşkilat 79.Bölüm Sezon Finali"
            )
        )
        assertEquals(setOf(14, 48, 79), teskilatFinals)
        assertEquals(1, DDiziParser.seasonForEpisode(14, teskilatFinals))
        assertEquals(2, DDiziParser.seasonForEpisode(15, teskilatFinals))
        assertEquals(2, DDiziParser.seasonForEpisode(48, teskilatFinals))
        assertEquals(3, DDiziParser.seasonForEpisode(49, teskilatFinals))
        assertEquals(3, DDiziParser.seasonForEpisode(79, teskilatFinals))
        assertEquals(4, DDiziParser.seasonForEpisode(80, teskilatFinals))
        assertEquals(4, DDiziParser.seasonForEpisode(81, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(15, teskilatFinals))
        assertEquals(34, DDiziParser.seasonLocalEpisodeNumber(48, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(49, teskilatFinals))
        assertEquals(12, DDiziParser.seasonLocalEpisodeNumber(60, teskilatFinals))
        assertEquals(31, DDiziParser.seasonLocalEpisodeNumber(79, teskilatFinals))
        assertEquals(1, DDiziParser.seasonLocalEpisodeNumber(80, teskilatFinals))
        assertEquals(null, DDiziParser.seasonForEpisode(null, teskilatFinals))

        val sevFinals = DDiziParser.seasonFinalEpisodeNumbers(
            listOf("Sevdiğim Sensin 15.Bölüm Sezon Finali")
        )
        assertEquals(setOf(15), sevFinals)
        assertEquals(1, DDiziParser.seasonForEpisode(15, sevFinals))
        assertEquals(15, DDiziParser.seasonLocalEpisodeNumber(15, sevFinals))
    }
}

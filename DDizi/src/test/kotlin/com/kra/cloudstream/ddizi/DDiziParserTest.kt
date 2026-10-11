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
}

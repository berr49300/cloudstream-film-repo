package com.kra.cloudstream.dizilla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DizillaParserTest {
    @Test
    fun parsesSeasonAndEpisodeFromSlug() {
        assertEquals(
            DizillaParser.EpisodePosition(season = 5, episode = 16),
            DizillaParser.parseEpisodePosition(
                "https://dizilla.now/breaking-bad-5-sezon-16-bolum-c02",
                ""
            )
        )
    }

    @Test
    fun parsesNumbersFromVisibleTurkishEpisodeTitle() {
        assertEquals(
            DizillaParser.EpisodePosition(season = 2, episode = 7),
            DizillaParser.parseEpisodePosition(
                "https://dizilla.now/dizi/example",
                "2. Sezon 7. Bölüm"
            )
        )
    }

    @Test
    fun removesEpisodeSuffixFromDisplayedSeriesTitle() {
        assertEquals(
            "Breaking Bad",
            DizillaParser.cleanSeriesTitle("Breaking Bad - 5. Sezon 16. Bölüm izle")
        )
    }

    @Test
    fun doesNotTreatSeriesSlugAsAnEpisode() {
        assertNull(
            DizillaParser.parseEpisodePosition(
                "https://dizilla.now/dizi/breaking-bad",
                "Breaking Bad"
            )
        )
    }
}

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmMakinesiParserTest {
    private val provider = FilmMakinesi()

    @Test
    fun parsesMovieAndSeriesCardsWithoutFakeNavigationResults() {
        val html = """
            <div class="item-relative">
              <a class="item" href="/film/mayday-2026/" data-title="Mayday" data-score="6.8">
                <img src="/uploads/mayday.jpg" alt="Mayday" />
                <div class="title">Mayday</div>
              </a>
            </div>
            <div class="item-relative">
              <a class="item" href="/dizi/below-2026/" data-title="Below" data-score="6.6">
                <img data-src="/uploads/below.jpg" alt="Below" />
                <div class="title">Below</div>
              </a>
            </div>
            <a href="/arama/">Ara</a>
            <a href="/kesfet/">Keşfet</a>
        """.trimIndent()

        val results = provider.parseSearchResults(Jsoup.parse(html, "https://filmmakinesi.to/"))

        assertEquals(2, results.size)
        assertEquals("Mayday", results[0].name)
        assertEquals(TvType.Movie, results[0].type)
        assertEquals("https://filmmakinesi.to/film/mayday-2026/", results[0].url)
        assertEquals("Below", results[1].name)
        assertEquals(TvType.TvSeries, results[1].type)
    }

    @Test
    fun parsesEpisodeNumbersAndSortsBySeasonThenEpisode() {
        val html = """
            <div class="episodes">
              <a href="/dizi/example-bolum-2/">2. Bölüm</a>
              <a href="/dizi/example-bolum-1/">1. Bölüm</a>
              <a href="/dizi/example-sezon-2-bolum-1/">2. Sezon 1. Bölüm</a>
            </div>
        """.trimIndent()

        val episodes = provider.parseEpisodes(
            Jsoup.parse(html, "https://filmmakinesi.to/dizi/example/"),
            "https://filmmakinesi.to/dizi/example/"
        )

        assertEquals(3, episodes.size)
        assertEquals(1, episodes[0].episode)
        assertEquals(2, episodes[1].episode)
        assertEquals(2, episodes[2].season)
        assertEquals(1, episodes[2].episode)
    }

    @Test
    fun subtitleTracksAreSeparateNormalizedAndOrdered() {
        val html = """
            <video>
              <track kind="subtitles" src="/subs/tr.vtt" srclang="tr" label="Türkçe" />
              <track kind="subtitles" src="/subs/en.vtt" srclang="en" label="English" />
              <track kind="subtitles" src="/subs/tr-forced.vtt" srclang="tr" label="Türkçe Forced" />
            </video>
        """.trimIndent()

        val tracks = FilmMakinesiSubtitleParser.parse(html, "https://closeload.filmmakinesi.to/player/")

        assertEquals(3, tracks.size)
        assertEquals(listOf("Türkçe", "İngilizce", "Türkçe Forced"), tracks.map { it.lang })
        assertEquals(
            listOf(
                "https://closeload.filmmakinesi.to/subs/tr.vtt",
                "https://closeload.filmmakinesi.to/subs/en.vtt",
                "https://closeload.filmmakinesi.to/subs/tr-forced.vtt"
            ),
            tracks.map { it.url }
        )
    }

    @Test
    fun findsDirectHlsAndMp4UrlsInPlayerMarkup() {
        val html = """
            <script>
              var playerA = { file: "https://cdn.example.test/video/master.m3u8" };
              var playerB = { "file": "https://cdn.example.test/video/movie.mp4?token=sample" };
            </script>
        """.trimIndent()

        val urls = FilmMakinesiCloseLoadExtractor.extractStreamUrls(
            html,
            "https://closeload.filmmakinesi.to/player/"
        )

        assertTrue(urls.any { it.contains("master.m3u8") })
        assertTrue(urls.any { it.contains("movie.mp4?token=sample") })
    }

    @Test
    fun supportsLegacyCloseLoadPayloadDecoderFixture() {
        val js = """
            function nc0(uvx) {
                var anzgu = uvx.join('');
                var d25w = "xj1kV8NUjhX1BYHA71jJadM";
                var om88 = "bvY";
                return "";
            }
        """.trimIndent()
        val chunks = """["==f1zOuNp","aPbkyAql0","fN2lM6ffq","6rF/nd13+","acP9LSu8g","utaac/6E8","e+vmPliVe","CWodoyhm/","yJtXiB/6N","yVcH41h5y","4Ct7u453Q","+2Of5ZUpn","7G+nOjjGN","TN+BGg+Rp","v1Eoi5j7u","43W+jk8+y"]"""

        val decoded = FilmMakinesiCloseLoadExtractor.decodeLegacyCloseLoad(js, chunks)

        assertEquals(
            "https://srv9.cdnimages3055.shop/hls/mayday-2026-webdl-trdual-tt28014327mp4-OSxZYB25jjD4.mp4/txt/master.txt",
            decoded
        )
    }
}

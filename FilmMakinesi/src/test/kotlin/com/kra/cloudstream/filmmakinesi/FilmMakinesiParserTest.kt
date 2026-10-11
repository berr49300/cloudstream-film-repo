/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.kra.cloudstream.filmmakinesi

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmMakinesiParserTest {
    @Test
    fun discoversUnknownPlayerHostWhenUrlIsInsidePlayerScript() {
        val provider = FilmMakinesi()
        val html = """
            <html><body>
              <script>const player = { source: "https://media-cdn.example.test/play/abc?token=1" };</script>
              <div>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</div>
              <a href="https://unrelated.example.test/page">External article</a>
            </body></html>
        """.trimIndent()
        val sources = provider.parseSourceCandidates(
            Jsoup.parse(html, "https://filmmakinesi.to/film/example/"),
            html,
            "https://filmmakinesi.to/film/example/"
        )

        assertTrue(sources.any { it.url == "https://media-cdn.example.test/play/abc?token=1" })
        assertTrue(sources.none { it.url == "https://unrelated.example.test/page" })
    }

    @Test
    fun subtitleTracksAreSeparateNormalizedAndOrdered() {
        val html = """
            <video>
              <track kind="subtitles" src="/subs/tr.vtt" srclang="tr" label="Türkçe" />
              <track kind="subtitles" src="/subs/caption2.vtt" label="İngilizce" />
              <track kind="subtitles" src="/subs/tr-forced.vtt" srclang="tr" label="Türkçe Forced" />
            </video>
        """.trimIndent()

        val tracks = FilmMakinesiSubtitleParser.parse(
            html,
            "https://closeload.filmmakinesi.to/player/"
        )

        assertEquals(3, tracks.size)
        assertEquals(listOf("Türkçe", "İngilizce", "Türkçe Forced"), tracks.map { it.lang })
        assertEquals(
            listOf(
                "https://closeload.filmmakinesi.to/subs/tr.vtt",
                "https://closeload.filmmakinesi.to/subs/caption2.vtt",
                "https://closeload.filmmakinesi.to/subs/tr-forced.vtt"
            ),
            tracks.map { it.url }
        )
    }

    @Test
    fun forcedMetadataDoesNotLeakBetweenNeighboringTracks() {
        val html = """
            var player = {
              tracks: [
                {"file": "/captions/tur-default.vtt", "label": "Türkçe"},
                {"file": "/captions/second.vtt", "label": "İngilizce"},
                {"file": "/captions/tur-forced.vtt", "label": "Türkçe Forced"}
              ]
            };
        """.trimIndent()

        val tracks = FilmMakinesiSubtitleParser.parse(
            html,
            "https://closeload.filmmakinesi.to/player/"
        )

        assertEquals(3, tracks.size)
        assertEquals("Türkçe", tracks.first().lang)
        assertEquals("İngilizce", tracks[1].lang)
        assertEquals("Türkçe Forced", tracks[2].lang)
    }

    @Test
    fun deduplicatesSameSubtitleUrlAndResolvesRelativeTracks() {
        val html = """
            <track src="/subs/tr.srt" srclang="tr" label="Türkçe" />
            <track src="https://example.test/subs/en.srt" srclang="en" label="English" />
            <script>{"file":"/subs/tr.srt","label":"Turkish"}</script>
        """.trimIndent()

        val tracks = FilmMakinesiSubtitleParser.parse(
            html,
            "https://example.test/movie/player/"
        )

        assertEquals(2, tracks.size)
        assertTrue(tracks.any { it.lang == "Türkçe" && it.url == "https://example.test/subs/tr.srt" })
        assertTrue(tracks.any { it.lang == "İngilizce" && it.url == "https://example.test/subs/en.srt" })
    }


    @Test
    fun excludesYoutubeHostsButKeepsSitePlayers() {
        assertTrue(FilmMakinesiSourceRules.isYouTubeHost("youtube.com"))
        assertTrue(FilmMakinesiSourceRules.isYouTubeHost("www.youtube.com"))
        assertTrue(FilmMakinesiSourceRules.isYouTubeHost("youtu.be"))
        assertTrue(!FilmMakinesiSourceRules.isYouTubeHost("rapid.filmmakinesi.to"))
        assertTrue(!FilmMakinesiSourceRules.isYouTubeHost("closeload.filmmakinesi.to"))
    }

    @Test
    fun escapedSubtitleUrlsAndLanguageNamesAreDecoded() {
        val html = """{"file":"\\/subtitles\\/tr.vtt","label":"T\u00fcrk\u00e7e"}"""

        val tracks = FilmMakinesiSubtitleParser.parse(html, "https://example.test/player/")

        assertEquals(1, tracks.size)
        assertEquals("Türkçe", tracks.single().lang)
        assertEquals("https://example.test/subtitles/tr.vtt", tracks.single().url)
    }
}

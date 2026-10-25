package com.kra.cloudstream.webteizle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebteIzleResolverBankTest {
    @Test
    fun playbackLanguageRecognizesOnlyDubAndSubtitleRoutes() {
        assertEquals("0" to "Dublaj", WebteIzleResolverBank.playbackLanguage(
            "https://webteizle.info/izle/dublaj/ornek-film"
        ))
        assertEquals("1" to "Altyazı", WebteIzleResolverBank.playbackLanguage(
            "https://webteizle.info/izle/altyazi/ornek-film"
        ))
        assertEquals(null, WebteIzleResolverBank.playbackLanguage(
            "https://webteizle.info/hakkinda/ornek-film"
        ))
    }

    @Test
    fun playbackDiscoveryKeepsOnlySameSiteLanguageRoutes() {
        val html = """
            <a href="/izle/dublaj/ornek-film">Dublaj</a>
            <a href="/izle/altyazi/ornek-film">Altyazı</a>
            <a href="https://other.example/izle/dublaj/fake">External</a>
            <a href="/fragman/ornek-film">Trailer</a>
        """.trimIndent()

        val routes = WebteIzleResolverBank.findPlaybackPageUrls(
            html,
            "https://webteizle.info/hakkinda/ornek-film",
            "https://webteizle.info"
        )

        assertEquals(
            listOf(
                "https://webteizle.info/izle/dublaj/ornek-film",
                "https://webteizle.info/izle/altyazi/ornek-film"
            ),
            routes
        )
    }

    @Test
    fun onlyVidMolyIframesAreAccepted() {
        val html = """
            <iframe src="https://vidmoly.biz/embed-MOVIE123.html"></iframe>
            <iframe src="https://filemoon.to/e/OTHER123"></iframe>
            <iframe src="https://youtube.com/embed/FAKE123"></iframe>
        """.trimIndent()

        val urls = WebteIzleResolverBank.findVidMolyPlayerUrls(
            html,
            "https://webteizle.info/ajax/dataEmbed.asp"
        )

        assertEquals(listOf("https://vidmoly.biz/embed-MOVIE123.html"), urls)
        assertTrue(urls.all { WebteIzleResolverBank.isVidMolyUrl(it) })
    }

    @Test
    fun observedVidMolyFunctionTriesKnownHostsButNeverOtherPlayers() {
        val html = """
            <script>
                vidmoly('MOVIE123', 'config');
                filemoon('OTHER123', 'config');
                okru('THIRD123', 'config');
            </script>
        """.trimIndent()

        val urls = WebteIzleResolverBank.findVidMolyPlayerUrls(
            html,
            "https://webteizle.info/ajax/dataEmbed.asp"
        )

        assertEquals(4, urls.size)
        assertTrue(urls.contains("https://vidmoly.biz/embed-MOVIE123.html"))
        assertTrue(urls.contains("https://vidmoly.net/embed-MOVIE123.html"))
        assertTrue(urls.none { it.contains("filemoon", true) || it.contains("ok.ru", true) })
    }

    @Test
    fun onlyAbsoluteHlsFileValuesAreAcceptedFromVidMolyPage() {
        assertEquals(
            "https://cdn.example/master.m3u8?token=abc",
            WebteIzleResolverBank.extractVidMolyMasterPlaylist(
                """var player = { file: "https://cdn.example/master.m3u8?token=abc" };"""
            )
        )
        assertEquals(
            null,
            WebteIzleResolverBank.extractVidMolyMasterPlaylist(
                """var player = { file: "https://youtube.com/trailer.m3u8" };"""
            )
        )
        assertEquals(
            null,
            WebteIzleResolverBank.extractVidMolyMasterPlaylist(
                """var player = { file: "/relative/master.m3u8" };"""
            )
        )
    }

    @Test
    fun challengeDetectionFailsClosed() {
        assertTrue(WebteIzleResolverBank.isAccessChallenge("<title>Just a moment...</title>"))
        assertTrue(WebteIzleResolverBank.isAccessChallenge("cf-chl-abc challenge-platform"))
        assertFalse(WebteIzleResolverBank.isAccessChallenge("<html><body>Player</body></html>"))
    }

    @Test
    fun youtubeAndGoogleVideoAreNotPlayableFilmSources() {
        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://youtu.be/abc123"))
        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://r1.googlevideo.com/videoplayback"))
        assertFalse(WebteIzleResolverBank.isYouTubeUrl("https://vidmoly.biz/embed-abc123.html"))
    }
}

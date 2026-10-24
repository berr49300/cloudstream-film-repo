package com.kra.cloudstream.webteizle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebteIzleResolverBankTest {
    @Test
    fun inventoryHasAllFortyOneUniqueResolverIdsAndNames() {
        val attempts = WebteIzleResolverBank.inspect("<html><body>no sources</body></html>", "https://webteizle.info/")
        assertEquals((1..41).toList(), attempts.map { it.resolverId })
        assertEquals(41, attempts.map { it.resolverName }.distinct().size)
        assertEquals(WebteIzleProbeStatus.NOT_APPLICABLE, attempts.first { it.resolverId == 11 }.status)
        assertEquals(WebteIzleProbeStatus.NOT_TESTED, attempts.first { it.resolverId == 37 }.status)
    }

    @Test
    fun sourceCandidatesKeepTheirResolverIdsAndLanguageTracks() {
        val html = """
            <!doctype html>
            <html><head>
              <script>
                const primarySource = "https://cdn.example/movie.mp4";
                var player = {"file":"https://cdn.example/cast.m3u8"};
                var escapedSource = "https:\/\/cdn.example/escaped.m3u8";
                var encodedSource = atob("aHR0cHM6Ly9jZG4uZXhhbXBsZS9jb2RlZC5tcDQ=");
              </script>
            </head><body>
              <video><source src="https://cdn.example/native.m3u8"></video>
              <div data-url="https://player.example/embed/data-url"></div>
              <iframe src="https://player.example/embed/iframe"></iframe>
              <track kind="subtitles" srclang="tr" label="Türkçe" src="/subs/tr.vtt">
            </body></html>
        """.trimIndent()

        val attempts = WebteIzleResolverBank.inspect(html, "https://webteizle.info/hakkinda/sample")
        fun attempt(id: Int) = attempts.first { it.resolverId == id }

        assertTrue(attempt(1).candidates.any { it.url == "https://cdn.example/native.m3u8" })
        assertTrue(attempt(2).candidates.any { it.url == "https://player.example/embed/data-url" })
        assertTrue(attempt(10).candidates.any { it.url == "https://cdn.example/movie.mp4" })
        assertTrue(attempt(13).candidates.any { it.url == "https://cdn.example/coded.mp4" })
        assertTrue(attempt(15).candidates.any { it.url == "https://player.example/embed/iframe" })
        assertTrue(attempt(17).candidates.any { it.url == "https://player.example/embed/iframe" })
        assertTrue(attempt(23).candidates.any { it.url == "https://cdn.example/native.m3u8" })
        assertTrue(attempt(26).candidates.any { it.url == "https://cdn.example/movie.mp4" })

        val subtitle = attempt(35).candidates.firstOrNull { it.url == "https://webteizle.info/subs/tr.vtt" }
        assertNotNull(subtitle)
        assertEquals("tr", subtitle!!.language)
    }

    @Test
    fun hlsVariantsAndSegmentsAreNotConfusedWithFullVideoFiles() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="tr",NAME="Türkçe",URI="subs/tr.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            low/index.m3u8
            #EXTINF:6,
            segment-001.ts
        """.trimIndent()

        val attempts = WebteIzleResolverBank.inspect(manifest, "https://media.example/master.m3u8")
        val variants = attempts.first { it.resolverId == 24 }
        val segments = attempts.first { it.resolverId == 28 }
        val tracks = attempts.first { it.resolverId == 35 }

        assertTrue(variants.candidates.any { it.url == "https://media.example/low/index.m3u8" })
        assertEquals(WebteIzleProbeStatus.SIGNAL_FOUND, segments.status)
        assertTrue(segments.candidates.isEmpty())
        assertTrue(tracks.candidates.any { it.url == "https://media.example/subs/tr.m3u8" })
    }

    @Test
    fun nestedIframeUrlsAreOnlyAssignedToIdSixteenAfterAChildPageIsFetched() {
        val parentUrl = "https://player.example/embed/parent"
        val childHtml = """<iframe src="/embed/child"></iframe>"""
        val nested = WebteIzleResolverBank.findIframeUrls(childHtml, parentUrl)
        val attempts = WebteIzleResolverBank.inspect(
            html = childHtml,
            baseUrl = parentUrl,
            nestedIframeUrls = nested
        )
        val nestedAttempt = attempts.first { it.resolverId == 16 }

        assertEquals("https://player.example/embed/child", nested.single())
        assertTrue(nestedAttempt.candidates.any { it.url == "https://player.example/embed/child" })
        assertEquals(WebteIzleProbeStatus.CANDIDATE_FOUND, nestedAttempt.status)
    }

    @Test
    fun signedUrlCandidateIsKeptUnderItsOwnIdWithoutPuttingQueryIntoReason() {
        val html = """<video src="https://cdn.example/movie.mp4?token=do-not-log&expires=999999">"""
        val attempts = WebteIzleResolverBank.inspect(html, "https://webteizle.info/")
        val signed = attempts.first { it.resolverId == 32 }

        assertTrue(signed.candidates.any { it.url.contains("token=do-not-log") })
        assertTrue(!signed.reason.contains("do-not-log"))
    }

    @Test
    fun youtubeTrailersAndBehindTheScenesLinksAreNeverCandidates() {
        val html = """
            <html><head>
              <script>
                const trailer = {"file":"https://www.youtube.com/watch?v=trailer123"};
                const shortLink = "https://youtu.be/behindthescenes";
              </script>
            </head><body>
              <iframe src="https://www.youtube-nocookie.com/embed/clip123"></iframe>
              <video src="https://r1---sn.googlevideo.com/videoplayback?id=example"></video>
            </body></html>
        """.trimIndent()

        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://www.youtube.com/watch?v=trailer123"))
        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://youtu.be/behindthescenes"))
        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://www.youtube-nocookie.com/embed/clip123"))
        assertTrue(WebteIzleResolverBank.isYouTubeUrl("https://r1---sn.googlevideo.com/videoplayback?id=example"))
        assertTrue(!WebteIzleResolverBank.isYouTubeUrl("https://vidmoly.to/embed-episode123.html"))

        val attempts = WebteIzleResolverBank.inspect(html, "https://webteizle.info/hakkinda/sample")

        assertTrue(attempts.all { attempt ->
            attempt.candidates.none { WebteIzleResolverBank.isYouTubeUrl(it.url) }
        })
        assertTrue(attempts.first { it.resolverId == 7 }.candidates.isEmpty())
        assertTrue(attempts.first { it.resolverId == 15 }.candidates.isEmpty())
        assertTrue(attempts.first { it.resolverId == 17 }.candidates.isEmpty())
        assertTrue(attempts.first { it.resolverId == 36 }.candidates.isEmpty())
    }

    @Test
    fun knownPlayerFamiliesAreRecognizedEvenWithoutMediaFileExtensions() {
        val html = """
            <html><body>
              <div data-url="https://vidmoly.to/v/episode-123"></div>
              <div data-file="https://filemoon.sx/e/episode-123"></div>
              <iframe src="https://pixeldrain.com/u/episode123"></iframe>
              <iframe src="https://ok.ru/videoembed/123456"></iframe>
            </body></html>
        """.trimIndent()

        val attempts = WebteIzleResolverBank.inspect(html, "https://webteizle.info/hakkinda/sample")
        val urls = attempts.flatMap { it.candidates }.map { it.url }

        assertTrue(urls.contains("https://vidmoly.to/v/episode-123"))
        assertTrue(urls.contains("https://filemoon.sx/e/episode-123"))
        assertTrue(urls.contains("https://pixeldrain.com/u/episode123"))
        assertTrue(urls.contains("https://ok.ru/videoembed/123456"))
        assertEquals("VidMoly", WebteIzleResolverBank.playerFamily("https://www.vidmoly.to/v/episode-123"))
        assertEquals("Filemoon", WebteIzleResolverBank.playerFamily("https://filemoon.sx/e/episode-123"))
        assertEquals("PixelDrain", WebteIzleResolverBank.playerFamily("https://pixeldrain.com/u/episode123"))
        assertEquals("OK.ru", WebteIzleResolverBank.playerFamily("https://ok.ru/videoembed/123456"))
        assertEquals(null, WebteIzleResolverBank.playerFamily("https://filemoon.sx.example.org/e/episode-123"))
    }

    @Test
    fun cloudflareChallengeIsDiagnosedWithoutTreatingItAsMoviePage() {
        val challenge = """
            <html><head><title>Just a moment...</title></head>
            <body><div id="challenge-platform">Checking your browser</div></body></html>
        """.trimIndent()
        val normal = """<html><body><h1>Example film</h1><video src="/media/movie.m3u8"></video></body></html>"""

        assertTrue(WebteIzleResolverBank.isAccessChallenge(challenge))
        assertTrue(!WebteIzleResolverBank.isAccessChallenge(normal))
    }


    @Test
    fun observedPlayerFunctionCallsProduceKnownEmbedCandidatesUnderResolverThirtyEight() {
        // Some AJAX responses are fragments rather than complete HTML/script documents.
        val html = """
            <div>
              vidmoly('VID123abc', 'config');
              filemoon('FILE456def', 'config');
              okru('OK789xyz', 'config');
            </div>
        """.trimIndent()

        val attempt = WebteIzleResolverBank.inspect(
            html,
            "https://webteizle.info/ajax/dataEmbed.asp"
        ).first { it.resolverId == 38 }

        val urls = attempt.candidates.map { it.url }
        assertTrue(urls.contains("https://vidmoly.net/embed-VID123abc.html"))
        assertTrue(urls.contains("https://vidmoly.to/embed-VID123abc.html"))
        assertTrue(urls.contains("https://vidmoly.biz/embed-VID123abc.html"))
        assertTrue(urls.contains("https://filemoon.sx/e/FILE456def"))
        assertTrue(urls.contains("https://odnoklassniki.ru/videoembed/OK789xyz"))
        assertEquals(5, urls.distinct().size)
        assertTrue(attempt.candidates.all { it.kind == WebteIzleCandidateKind.PLAYER })
    }


    @Test
    fun pixelSourceLabelIsRecordedWithoutInventingAnEmbedUrl() {
        val html = """<div class="player">source unavailable</div>"""
        val attempts = WebteIzleResolverBank.inspect(
            html = html,
            baseUrl = "https://webteizle.info/ajax/dataEmbed.asp",
            observedAjaxResponse = true,
            hintedPlayerName = "Pixel"
        )
        val attempt = attempts.first { it.resolverId == 39 }

        assertEquals(WebteIzleProbeStatus.SIGNAL_FOUND, attempt.status)
        assertTrue(attempt.reason.contains("no verified URL template"))
        assertTrue(attempt.candidates.isEmpty())
    }

    @Test
    fun pixelPlayerFunctionMarkerIsDiagnosedWithoutSynthesizingTheHost() {
        val html = """<script>pixel('PIXEL12345', 'config')</script>"""
        val attempt = WebteIzleResolverBank.inspect(
            html = html,
            baseUrl = "https://webteizle.info/ajax/dataEmbed.asp"
        ).first { it.resolverId == 39 }

        assertEquals(WebteIzleProbeStatus.SIGNAL_FOUND, attempt.status)
        assertTrue(attempt.candidates.isEmpty())
    }


    @Test
    fun observedHexEscapedFileAndPlayerJsVPayloadDecodeOnlyRealMediaUrls() {
        val expected = "https://cdn.example/episode.m3u8"
        val escaped = expected.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "\\x%02x".format(byte.toInt() and 0xff) }
        val contiguous = expected.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(expected, WebteIzleResolverBank.decodeKnownHexUrl(escaped))
        assertEquals(expected, WebteIzleResolverBank.decodeKnownHexUrl(contiguous))
        assertEquals(null, WebteIzleResolverBank.decodeKnownHexUrl("some-random-token"))
        assertEquals(null, WebteIzleResolverBank.decodeKnownHexUrl("https://cdn.example/episode.m3u8"))

        val html = """
            <html><body>
              <script>var player = { file: "$escaped" };</script>
              <iframe src="https://playerjs-three.vercel.app/?v=$contiguous"></iframe>
            </body></html>
        """.trimIndent()
        val attempt = WebteIzleResolverBank.inspect(
            html = html,
            baseUrl = "https://webteizle.info/ajax/dataEmbed.asp"
        ).first { it.resolverId == 40 }

        assertTrue(attempt.candidates.any { it.url == expected && it.kind == WebteIzleCandidateKind.MEDIA })
        assertEquals(1, attempt.candidates.map { it.url }.distinct().size)
    }

    @Test
    fun hexDecoderDoesNotConvertTrailerOrUnknownHostGuessIntoWebteIzleMedia() {
        val youtube = "https://www.youtube.com/watch?v=trailer123"
        val encoded = youtube.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "\\x%02x".format(byte.toInt() and 0xff) }
        val html = """<script>var file = "$encoded";</script>"""
        val attempt = WebteIzleResolverBank.inspect(
            html = html,
            baseUrl = "https://webteizle.info/ajax/dataEmbed.asp"
        ).first { it.resolverId == 40 }

        assertTrue(attempt.candidates.isEmpty())
        assertTrue(WebteIzleResolverBank.isYouTubeUrl(
            WebteIzleResolverBank.decodeKnownHexUrl(encoded)!!
        ))
    }


    @Test
    fun vidmolyMasterPlaylistResolverExtractsOnlyAbsoluteHlsFileField() {
        val expected = "https://cdn.example/master.m3u8?token=safe-fixture"
        val html = """
            <script>
              jwplayer("v").setup({sources:[{file:"$expected", type:"hls"}]});
            </script>
        """.trimIndent()

        assertEquals(expected, WebteIzleResolverBank.extractVidMolyMasterPlaylist(html))
        assertEquals(
            null,
            WebteIzleResolverBank.extractVidMolyMasterPlaylist(
                """<script>jwplayer().setup({sources:[{file:"//cdn.example/master.m3u8"}]})</script>"""
            )
        )
        assertEquals(
            null,
            WebteIzleResolverBank.extractVidMolyMasterPlaylist(
                """<script>jwplayer().setup({sources:[{file:"https://www.youtube.com/watch?v=trailer.m3u8"}]})</script>"""
            )
        )
    }


    @Test
    fun detailPageFindsOnlySameSiteDubbedAndSubtitlePlaybackRoutes() {
        val html = """
            <html><body>
              <a href="/izle/altyazi/love-language">Altyazı ile izle</a>
              <a href="/izle/dublaj/love-language">Dublaj ile izle</a>
              <a href="https://www.youtube.com/embed/trailer-id">Fragman</a>
              <a href="https://malicious.example/izle/altyazi/fake">external fake route</a>
              <a href="/hakkinda/love-language">detail page</a>
            </body></html>
        """.trimIndent()

        val routes = WebteIzleResolverBank.findPlaybackPageUrls(
            html = html,
            baseUrl = "https://webteizle.info/hakkinda/love-language",
            siteBaseUrl = "https://webteizle.info"
        )

        assertEquals(
            listOf(
                "https://webteizle.info/izle/altyazi/love-language",
                "https://webteizle.info/izle/dublaj/love-language"
            ),
            routes
        )
        assertTrue(routes.none { WebteIzleResolverBank.isYouTubeUrl(it) })
        assertTrue(routes.none { it.contains("/hakkinda/") })
    }

    @Test
    fun detailPageWithOnlyTrailerDoesNotCreatePlaybackRoute() {
        val html = """
            <html><body>
              <a href="https://www.youtube.com/embed/trailer-id">Fragman</a>
              <a href="/hakkinda/another-movie">Benzer film</a>
            </body></html>
        """.trimIndent()

        val routes = WebteIzleResolverBank.findPlaybackPageUrls(
            html = html,
            baseUrl = "https://webteizle.info/hakkinda/love-language",
            siteBaseUrl = "https://webteizle.info"
        )

        assertTrue(routes.isEmpty())
    }

}

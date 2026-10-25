package com.kra.cloudstream.selcukflix

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixResolverBankTest {
    @Test
    fun resolverCatalogKeepsAllThirtySevenIds() {
        val catalog = SelcukFlixResolverBank.resolverCatalog()
        assertEquals((1..37).toList(), catalog.map { it.first })
        assertEquals("Html5SourceResolver", catalog.first().second)
        assertEquals("ManualNetworkEvidenceResolver", catalog.last().second)
    }

    @Test
    fun iframeCandidateIsVisibleAsSeparateNumberedStrategies() {
        val html = """<div id="player"><iframe src="//embed.example/e/abc123"></iframe></div>"""
        val results = SelcukFlixResolverBank.extractCandidates(
            html,
            "https://selcukflix.app/dizi/test-1-sezon-1-bolum",
            "https://selcukflix.app",
            fromObservedApi = true
        )

        assertTrue(results.any { it.id == 15 && it.url == "https://embed.example/e/abc123" })
        assertTrue(results.any { it.id == 17 && it.url == "https://embed.example/e/abc123" })
        assertTrue(results.any { it.id == 33 && it.url == "https://embed.example/e/abc123" })
        assertTrue(results.any { it.id == 36 && it.url == "https://embed.example/e/abc123" })
        assertTrue(results.all { it.url.startsWith("https://") })
    }

    @Test
    fun detectsHtml5HlsDashAndDirectFilesWithoutConfusingTheMediaType() {
        val html = """
            <video><source src="/media/master.m3u8" type="application/vnd.apple.mpegurl"></video>
            <iframe src="https://players.example/embed/xyz"></iframe>
            <script>const dash = "https://cdn.example/stream.mpd";</script>
            <script>const file = "https://cdn.example/movie.mp4";</script>
        """.trimIndent()

        val results = SelcukFlixResolverBank.extractCandidates(
            html,
            "https://selcukflix.app/film/sample",
            "https://selcukflix.app"
        )
        assertTrue(results.any { it.id == 1 && it.url == "https://selcukflix.app/media/master.m3u8" && it.kind == SelcukMediaKind.HLS })
        assertTrue(results.any { it.id == 25 && it.url == "https://cdn.example/stream.mpd" && it.kind == SelcukMediaKind.DASH })
        assertTrue(results.any { it.id == 26 && it.url == "https://cdn.example/movie.mp4" && it.kind == SelcukMediaKind.VIDEO })
    }

    @Test
    fun decodesObservedSecureDataEnvelopeWithoutExecutingJavaScript() {
        val json = """{"contentItem":{"title":"Example"},"relatedResults":{"source_content":"https://cdn.example/master.m3u8"}}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"secureData":"$encoded"}}}</script>"""

        assertEquals(json, SelcukFlixResolverBank.extractEmbeddedPayload(html))
        val results = SelcukFlixResolverBank.extractCandidates(
            html,
            "https://selcukflix.app/film/example",
            "https://selcukflix.app"
        )
        assertTrue(results.any { it.id == 14 && it.url == "https://cdn.example/master.m3u8" })
    }

    @Test
    fun suppressesYoutubeAndGoogleVideoTrailerCandidates() {
        val html = """
            <iframe src="https://www.youtube.com/embed/abc123"></iframe>
            <script>const clip = "https://rr1---sn.googlevideo.com/videoplayback?id=abc";</script>
        """.trimIndent()
        val results = SelcukFlixResolverBank.extractCandidates(
            html,
            "https://selcukflix.app/film/example",
            "https://selcukflix.app"
        )

        assertFalse(results.any { SelcukFlixResolverBank.isYouTubeUrl(it.url) })
    }

    @Test
    fun cloudflareChallengeFailsClosed() {
        val html = "<html><title>Just a moment...</title><div id='cf-chl-widget'></div></html>"
        assertTrue(SelcukFlixResolverBank.isAccessChallenge(html))
        assertTrue(
            SelcukFlixResolverBank.extractCandidates(
                html,
                "https://selcukflix.app/",
                "https://selcukflix.app"
            ).isEmpty()
        )
    }

    @Test
    fun hlsMasterPlaylistProducesVariantCandidatesAndSubtitleTracks() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Turkish",LANGUAGE="tr",URI="subs/tr.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,SUBTITLES="subs"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720,SUBTITLES="subs"
            720/index.m3u8
        """.trimIndent()

        val variants = SelcukFlixResolverBank.extractHlsVariants(
            manifest,
            "https://cdn.example/master.m3u8?sig=sensitive"
        )
        val subtitles = SelcukFlixResolverBank.extractHlsSubtitles(
            manifest,
            "https://cdn.example/master.m3u8?sig=sensitive"
        )

        assertEquals(2, variants.size)
        assertTrue(variants.all { it.id == 24 && it.kind == SelcukMediaKind.HLS })
        assertEquals("https://cdn.example/360/index.m3u8", variants[0].url)
        assertEquals(1, subtitles.size)
        assertEquals("Turkish", subtitles.single().language)
        assertEquals("https://cdn.example/subs/tr.m3u8", subtitles.single().url)
    }

    @Test
    fun subtitleDiscoveryAcceptsTrackTagsAndRejectsYoutubeLinks() {
        val html = """
            <track kind="subtitles" srclang="tr" label="Turkish" src="/captions/tr.vtt">
            <track kind="subtitles" srclang="en" src="https://www.youtube.com/watch?v=abc">
        """.trimIndent()
        val subtitles = SelcukFlixResolverBank.extractSubtitles(html, "https://selcukflix.app/film/example")
        assertEquals(1, subtitles.size)
        assertEquals("Turkish", subtitles.single().language)
        assertEquals("https://selcukflix.app/captions/tr.vtt", subtitles.single().url)
    }
}

package com.kra.cloudstream.webteizle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebteIzleResolverBankTest {
    @Test
    fun inventoryHasAllThirtySevenUniqueResolverIdsAndNames() {
        val attempts = WebteIzleResolverBank.inspect("<html><body>no sources</body></html>", "https://webteizle.info/")
        assertEquals((1..37).toList(), attempts.map { it.resolverId })
        assertEquals(37, attempts.map { it.resolverName }.distinct().size)
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
    fun signedUrlCandidateIsKeptUnderItsOwnIdWithoutPuttingQueryIntoReason() {
        val html = """<video src="https://cdn.example/movie.mp4?token=do-not-log&expires=999999">"""
        val attempts = WebteIzleResolverBank.inspect(html, "https://webteizle.info/")
        val signed = attempts.first { it.resolverId == 32 }

        assertTrue(signed.candidates.any { it.url.contains("token=do-not-log") })
        assertTrue(!signed.reason.contains("do-not-log"))
    }
}

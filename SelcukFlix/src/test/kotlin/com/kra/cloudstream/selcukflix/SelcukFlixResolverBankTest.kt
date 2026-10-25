package com.kra.cloudstream.selcukflix

import java.util.Base64
import java.security.MessageDigest
import com.fasterxml.jackson.databind.ObjectMapper
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixResolverBankTest {
    @Test
    fun repairsMalformedSelcukFlixPageUrls() {
        val base = "https://selcukflix.app"
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("https://selcukflix.appdizi/carrie", base)
        )
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("selcukflix.appdizi/carrie", base)
        )
        assertEquals(
            "https://selcukflix.app/film/example",
            SelcukFlixResolverBank.normalizeSiteUrl("https://selcukflix.appfilm/example", base)
        )
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("dizi/carrie", base)
        )
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("https://selcukflix.net/dizi/carrie", base)
        )
    }

    @Test
    fun resolverCatalogKeepsAllThirtySevenIds() {
        val catalog = SelcukFlixResolverBank.resolverCatalog()
        assertEquals((1..39).toList(), catalog.map { it.first })
        assertEquals("Html5SourceResolver", catalog.first().second)
        assertEquals("PichiveMasterPlaylistResolver", catalog.last().second)
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
    fun parsesCurrentSnakeCaseSeasonAndEpisodePayload() {
        val root = ObjectMapper().readTree(
            """
            {
              "RelatedResults": {
                "getSerieSeasonAndEpisodes": {
                  "result": [
                    {
                      "season_no": 2,
                      "episodes": [
                        {"episode_no": 2, "episode_text": "İkinci Bölüm", "used_slug": "dizi/carrie/2-sezon-2-bolum"},
                        {"episode_no": 1, "episode_text": "İlk Bölüm", "used_slug": "dizi/carrie/2-sezon-1-bolum"}
                      ]
                    },
                    {
                      "season_no": 1,
                      "episodes": [
                        {"episode_no": 1, "episode_text": "Pilot", "used_slug": "dizi/carrie/1-sezon-1-bolum"}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val episodes = SelcukFlixResolverBank.parseEpisodeRecords(root, "https://selcukflix.app")
        assertEquals(listOf(1, 2, 2), episodes.map { it.season })
        assertEquals(listOf(1, 1, 2), episodes.map { it.episode })
        assertEquals("https://selcukflix.app/dizi/carrie/1-sezon-1-bolum", episodes.first().url)
        assertEquals("Pilot", episodes.first().name)
        assertEquals(3, episodes.size)
    }

    @Test
    fun parsesEpisodeRecordsFromCurrentContentResultEnvelope() {
        val root = ObjectMapper().readTree(
            """
            {
              "content": {
                "result": {
                  "FindedType": "Series",
                  "RelatedResults": {
                    "getSerieSeasonAndEpisodes": {
                      "result": [
                        {
                          "season_no": 1,
                          "episodes": [
                            {
                              "episode_no": 1,
                              "episode_text": "Pilot",
                              "used_slug": "dizi/carrie/sezon-1/bolum-1"
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )
        val episodes = SelcukFlixResolverBank.parseEpisodeRecords(root, "https://selcukflix.app")
        assertEquals(1, episodes.size)
        assertEquals("Pilot", episodes.single().name)
        assertEquals(1, episodes.single().season)
        assertEquals(1, episodes.single().episode)
        assertEquals("https://selcukflix.app/dizi/carrie/sezon-1/bolum-1", episodes.single().url)
    }

    @Test
    fun extractsOnlyIframeUrlsActuallyPresentInSourceContent() {
        val payload = """
            {
              "content": {
                "result": {
                  "RelatedResults": {
                    "getEpisodeSources": {
                      "result": [
                        {
                          "source_content": "<iframe src=\\\"https://four.pichive.online/iframe.php?v=sample\\\" title=\\\"player\\\"></iframe>"
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        assertEquals(
            listOf("https://four.pichive.online/iframe.php?v=sample"),
            SelcukFlixResolverBank.extractSourceIframes(payload, "https://selcukflix.app/dizi/carrie/sezon-1/bolum-1")
        )
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

    @Test
    fun decryptsCurrentSitePayloadEnvelope() {
        val plain = """{"result":[{"used_slug":"dizi/demo","original_title":"Demo"}]}"""
        val key = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))

        assertEquals(plain, SelcukFlixResolverBank.decodeSitePayload(encoded))
    }

    @Test
    fun decryptsAlternativeDerivedSitePayloadEnvelope() {
        val plain = """{"content":{"result":{"FindedType":"Series"}}}"""
        val derivedKey = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
        ).take(32).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), IvParameterSpec(ByteArray(16)))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))

        assertEquals(plain, SelcukFlixResolverBank.decodeSitePayload(encoded))
    }

    @Test
    fun discoversPichiveTokenAndSourceApiWithoutGuessingHost() {
        val iframeUrl = "https://four.pichive.online/iframe.php?v=shorttoken123"
        val html = """<script>openPlayer('this_is_a_long_observed_player_token_12345', true)</script>"""
        val token = SelcukFlixResolverBank.extractPichiveToken(html)

        assertEquals("this_is_a_long_observed_player_token_12345", token)
        assertEquals(
            "https://four.pichive.online/source2.php?v=this_is_a_long_observed_player_token_12345",
            SelcukFlixResolverBank.pichiveSourceApiUrl(iframeUrl, token!!)
        )
        assertFalse(SelcukFlixResolverBank.isPichiveIframe("https://unrelated.example/iframe.php?v=abc"))
    }

    @Test
    fun producesSeparatePichiveRawAndDerivedPlaylistResolverIds() {
        val response = """
            {
              "state": true,
              "playlist": [
                {
                  "title": "Türkçe Dublaj",
                  "sources": [
                    {"file": "https://four.pichive.online/media/m.php?key=fixture", "label": "HD"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val candidates = SelcukFlixResolverBank.extractPichiveSourceCandidates(
            response,
            "https://four.pichive.online/iframe.php?v=fixture"
        )

        assertTrue(candidates.any { it.id == 38 && it.url.endsWith("m.php?key=fixture") })
        assertTrue(candidates.any {
            it.id == 39 && it.kind == SelcukMediaKind.HLS &&
                it.url.endsWith("master.m3u8?key=fixture")
        })
    }

}

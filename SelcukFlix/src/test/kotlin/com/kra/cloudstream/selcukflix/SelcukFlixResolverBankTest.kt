package com.kra.cloudstream.selcukflix

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelcukFlixResolverBankTest {
    @Test
    fun normalizesRelativeAndLegacySiteUrls() {
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("dizi/carrie", "https://selcukflix.app/")
        )
        assertEquals(
            "https://selcukflix.app/dizi/carrie",
            SelcukFlixResolverBank.normalizeSiteUrl("https://selcukflix.appdizi/carrie", "https://selcukflix.app/")
        )
        assertEquals(
            "https://selcukflix.app/film/example",
            SelcukFlixResolverBank.normalizeSiteUrl("https://selcukflix.net/film/example", "https://selcukflix.app/")
        )
    }

    @Test
    fun detectsChallengesWithoutTryingToSolveThem() {
        assertTrue(SelcukFlixResolverBank.isAccessChallenge("<title>Just a moment...</title><div id='cf-chl-widget'></div>"))
        assertFalse(SelcukFlixResolverBank.isAccessChallenge("<html><title>Example</title><video></video></html>"))
    }

    @Test
    fun decryptsCurrentSiteSecureDataEnvelope() {
        val plain = """{"RelatedResults":{"getEpisodeSources":{"result":[]}}}"""
        val key = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        assertEquals(plain, SelcukFlixResolverBank.decodeSitePayload(encoded))
    }

    @Test
    fun decryptsDerivedKeySecureDataEnvelope() {
        val plain = """{"content":{"result":{"FindedType":"Series"}}}"""
        val derivedKey = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
        ).take(32).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), IvParameterSpec(ByteArray(16)))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        assertEquals(plain, SelcukFlixResolverBank.decodeSitePayload(encoded))
    }

    @Test
    fun parsesSeasonAndEpisodeRecordsFromObservedPayloadShape() {
        val root = ObjectMapper().readTree(
            """
            {
              "content": {
                "result": {
                  "RelatedResults": {
                    "getSerieSeasonAndEpisodes": {
                      "result": [
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
              }
            }
            """.trimIndent()
        )
        val episodes = SelcukFlixResolverBank.parseEpisodeRecords(root, "https://selcukflix.app/")
        assertEquals(1, episodes.size)
        assertEquals(1, episodes.single().season)
        assertEquals(1, episodes.single().episode)
        assertEquals("Pilot", episodes.single().name)
        assertEquals("https://selcukflix.app/dizi/carrie/1-sezon-1-bolum", episodes.single().url)
    }

    @Test
    fun episodeFiltersUseCardMetadata() {
        assertTrue(SelcukFlixResolverBank.matchesEpisodeFilter("Türkçe dublaj", "dub"))
        assertTrue(SelcukFlixResolverBank.matchesEpisodeFilter("Altyazılı", "foreign"))
        assertTrue(SelcukFlixResolverBank.matchesEpisodeFilter("anime", "anime"))
        assertFalse(SelcukFlixResolverBank.matchesEpisodeFilter("drama", "anime"))
    }
    @Test
    fun extractsAllDistinctEpisodeAndMovieSourceContentFromNestedPayload() {
        val root = ObjectMapper().readTree(
            """{
              "content": {
                "result": {
                  "RelatedResults": {
                    "getEpisodeSources": {
                      "result": [
                        {
                          "quality_name": "720P",
                          "source_content": "<iframe src='https://player-one.example/iframe.php?v=fixture-one'></iframe>"
                        },
                        {
                          "quality_name": "1080P",
                          "source_content": "<iframe src='https://player-two.example/iframe.php?v=fixture-two'></iframe>"
                        }
                      ]
                    },
                    "getMoviePartSourcesById_12": {
                      "result": [
                        {
                          "qualityName": "FHD",
                          "sourceContent": "<iframe src='https://player-three.example/embed/fixture-three'></iframe>"
                        }
                      ]
                    }
                  }
                }
              }
            }"""
        )

        val sources = SelcukFlixResolverBank.sourceContentValues(root)

        assertEquals(3, sources.size)
        assertEquals(listOf("720P", "1080P", "FHD"), sources.map { it.label })
        assertTrue(sources[0].content.contains("player-one.example"))
        assertTrue(sources[1].content.contains("player-two.example"))
        assertTrue(sources[2].content.contains("player-three.example"))
    }

}

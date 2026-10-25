package com.kra.cloudstream.selcukflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ContentX / Pichive / PlayRu / DPlayer ailesi oynatıcılar için alan adından bağımsız çözücü.
 * Örnek: https://<host>/iframe.php?v=<hash>
 */
open class SelcukFlixContentXExtractor : ExtractorApi() {
    override val name            = "SelcukFlix ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    companion object {
        fun canHandle(url: String): Boolean = SelcukFlixContentXSourceParser.canHandle(url)
        fun uygunMu(url: String): Boolean = canHandle(url)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tamUrl  = if (url.startsWith("//")) "https:$url" else url
        val base    = Regex("""^(https?://[^/]+)""").find(tamUrl)?.groupValues?.get(1) ?: return
        val host    = base.substringAfter("://").substringAfter("four.").substringAfter("sn.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        val extRef  = referer ?: "$base/"
        val iSource = app.get(tamUrl, referer = extRef).text

        val subtitleTracks = SelcukFlixContentXSubtitleParser.parse(iSource, tamUrl)
        subtitleTracks.forEach { track ->
            val subtitleHost = runCatching { java.net.URI(track.url).host }.getOrNull().orEmpty()
            Log.d("SelcukFlixSubtitle", "Altyazı adayı ayrıştırıldı: ${track.label} (${subtitleHost})")
            subtitleCallback.invoke(
                newSubtitleFile(track.label, track.url) {
                    // Subtitle hosts may validate the embedding player as the request referer.
                    this.headers = mapOf("Referer" to tamUrl)
                }
            )
        }
        if (subtitleTracks.isEmpty()) {
            Log.d("SelcukFlixSubtitle", "Player sayfasında tanınan bir harici altyazı izi bulunamadı.")
        }

        val tokens = SelcukFlixContentXSourceParser.playerTokens(iSource)
        if (tokens.isEmpty()) {
            val directFiles = SelcukFlixContentXSourceParser.mediaFiles(iSource, tamUrl)
            directFiles.forEach { file ->
                callback.invoke(newExtractorLink(host, host, file, linkType(file)) {
                    this.referer = tamUrl
                    this.quality = Qualities.Unknown.value
                })
            }
            if (directFiles.isEmpty()) {
                android.util.Log.w("SelcukFlixContentX", "No openPlayer token or direct file was found in the ordinary player response.")
            }
            return
        }

        tokens.forEach { token ->
            kaynakEkle(base, token.token, extRef, tamUrl, "$host • ${token.label}", callback)
        }
    }

    private fun linkType(url: String): ExtractorLinkType? = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        Regex("""(?i)\\.(mp4|m4v|webm|mkv|avi|mov)(?:$|[?#])""").containsMatchIn(url) -> ExtractorLinkType.VIDEO
        else -> null
    }

    private suspend fun kaynakEkle(base: String, id: String, extRef: String, iframe: String, isim: String, callback: (ExtractorLink) -> Unit) {
        val apiUrl = "$base/source2.php?v=" + java.net.URLEncoder.encode(id, "UTF-8")
        val vidSource = try {
            app.get(apiUrl, referer = extRef).text
        } catch (_: Exception) {
            android.util.Log.w("SelcukFlixContentX", "$isim source API request failed.")
            return
        }
        if (SelcukFlixResolverBank.isAccessChallenge(vidSource)) {
            android.util.Log.w("SelcukFlixContentX", "$isim source API is blocked by an access challenge.")
            return
        }
        val media = SelcukFlixContentXSourceParser.mediaFiles(vidSource, apiUrl)
        if (media.isEmpty()) {
            android.util.Log.w("SelcukFlixContentX", "$isim source API returned no recognized media file.")
        }
        media.forEach { mediaUrl ->
            val mediaHost = runCatching { java.net.URI(mediaUrl).host }.getOrNull().orEmpty()
            Log.d("SelcukFlixContentX", "$isim source found on $mediaHost")
            callback.invoke(
                newExtractorLink(isim, isim, mediaUrl, linkType(mediaUrl) ?: ExtractorLinkType.M3U8) {
                    this.referer = iframe
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

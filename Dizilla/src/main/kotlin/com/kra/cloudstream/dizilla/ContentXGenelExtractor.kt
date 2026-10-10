package com.kra.cloudstream.dizilla

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ContentX / Pichive / PlayRu / DPlayer ailesi oynatıcılar için alan adından bağımsız çözücü.
 * Örnek: https://<host>/iframe.php?v=<hash>
 */
open class ContentXGenel : ExtractorApi() {
    override val name            = "ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    companion object {
        fun uygunMu(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.path.orEmpty().endsWith("/iframe.php", ignoreCase = true) &&
                uri.rawQuery.orEmpty().split("&").any { it.substringBefore("=") == "v" }
        }.getOrDefault(false)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val tamUrl  = if (url.startsWith("//")) "https:$url" else url
        val base    = Regex("""^(https?://[^/]+)""").find(tamUrl)?.groupValues?.get(1) ?: return
        val host    = base.substringAfter("://").substringAfter("four.").substringAfter("sn.").substringBefore(".")
            .replaceFirstChar { it.uppercase() }
        val extRef  = referer ?: "$base/"
        val iSource = app.get(tamUrl, referer = extRef).text

        val subtitleTracks = ContentXSubtitleParser.parse(iSource, tamUrl)
        subtitleTracks.forEach { track ->
            val subtitleHost = runCatching { java.net.URI(track.url).host }.getOrNull().orEmpty()
            Log.d("DizillaSubtitle", "Altyazı adayı ayrıştırıldı: ${track.label} (${subtitleHost})")
            subtitleCallback.invoke(
                newSubtitleFile(track.label, track.url) {
                    // Subtitle hosts may validate the embedding player as the request referer.
                    this.headers = mapOf("Referer" to tamUrl)
                }
            )
        }
        if (subtitleTracks.isEmpty()) {
            Log.d("DizillaSubtitle", "Player sayfasında tanınan bir harici altyazı izi bulunamadı.")
        }

        val iExtract = Regex("""window\.openPlayer\('([^']+)'""").find(iSource)?.groupValues?.get(1)
        if (iExtract == null) {
            // Eski tip oynatıcı: doğrudan file:"..." içerir
            val dosya = Regex("""file\s*:\s*"([^"]+)""").find(iSource)?.groupValues?.get(1) ?: return
            callback.invoke(
                newExtractorLink(host, host, dosya, if (dosya.contains(".m3u8")) ExtractorLinkType.M3U8 else null) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        kaynakEkle(base, iExtract, extRef, tamUrl, host, callback)

        val iDublaj = Regex(""",\"([^']+)\",\"Türkçe""").find(iSource)?.groupValues?.get(1)
        if (iDublaj != null && iDublaj != iExtract) {
            kaynakEkle(base, iDublaj, extRef, tamUrl, "$host Türkçe Dublaj", callback)
        }
    }

    private suspend fun kaynakEkle(base: String, id: String, extRef: String, iframe: String, isim: String, callback: (ExtractorLink) -> Unit) {
        val vidSource = app.get("$base/source2.php?v=$id", referer = extRef).text
        val m3uLink   = Regex("""file\":\"([^\"]+)""").find(vidSource)?.groupValues?.get(1)?.replace("\\", "") ?: return
        Log.d("DizillaContentX", "$isim » $m3uLink")

        callback.invoke(
            newExtractorLink(isim, isim, m3uLink, ExtractorLinkType.M3U8) {
                this.referer = iframe
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

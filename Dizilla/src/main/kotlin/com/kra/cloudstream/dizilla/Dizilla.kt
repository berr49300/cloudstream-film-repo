package com.kra.cloudstream.dizilla

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.kra.cloudstream.common.SourceCandidateParser
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.now"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/"                      to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle"     to "Yabancı Diziler",
        "${mainUrl}/anime-izle"            to "Anime",
        "${mainUrl}/kdrama-izle"           to "Kore Dizileri",
        "${mainUrl}/dizi-turu/aile"        to "Aile",
        "${mainUrl}/dizi-turu/aksiyon"     to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik"    to "Romantik",
        "${mainUrl}/dizi-turu/komedi"      to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site listeleri JavaScript ile sayfaladığı için yalnızca ilk sayfa alınır
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val document = app.get(request.data, referer = "${mainUrl}/").document
        val home = if (request.data.trimEnd('/') == mainUrl) {
            document.select("a[href]").filter {
                Regex("""-sezon-\d+-bolum""").containsMatchIn(it.attr("href")) && it.selectFirst("h3") != null
            }.mapNotNull { it.sonBolumler() }.distinctBy { it.url }
        } else {
            // Prefer catalog cards across a few observed wrapper variants. The older
            // span-only selector produced empty lists when the site used a different
            // card wrapper on Anime/K-drama pages, or when the poster was a sibling.
            // Prefer the site's known catalogue-card wrapper. Scanning every anchor
            // also picks up cards from recommendation rails and adjacent category widgets,
            // which can make Anime and Korean Drama rows appear mixed.
            val catalogAnchors = document.select(".prm-borderb a[href], span.prm-borderb[href]")
                .filter { it.isDizillaCatalogCard() }
            val candidates = if (catalogAnchors.isNotEmpty()) catalogAnchors else {
                // Layout fallback: only use anchors inside a main catalogue/list container,
                // not every link in the document.
                document.select("main a[href], #content a[href], .catalog a[href], .grid a[href]")
                    .filter { it.isDizillaCatalogCard() }
            }
            candidates
                .mapNotNull { it.diziler(categoryType(request.data)) }
                .distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.isDizillaCatalogCard(): Boolean {
        val absoluteUrl = fixUrlNull(attr("href")) ?: return false
        val path = runCatching { java.net.URI(absoluteUrl).path.orEmpty() }.getOrDefault("")
        if (!Regex("""/(?:dizi|anime|kdrama|kore-dizileri)/.+""").containsMatchIn(path)) return false

        val excluded = parents().any { parent ->
            val marker = "${parent.id()} ${parent.className()}".lowercase()
            parent.normalName() in setOf("aside", "nav", "footer") ||
                listOf("sidebar", "recommend", "related", "footer", "widget").any { marker.contains(it) }
        }
        if (excluded) return false

        val card = closest(".prm-borderb, article, li, [class*=card]") ?: parent() ?: this
        val image = selectFirst("img") ?: card.selectFirst("img")
        val title = attr("title").isNotBlank() ||
            selectFirst("h2, h3, h4, [class*=title], [class*=line-clamp]") != null ||
            card.selectFirst("h2, h3, h4, [class*=title], [class*=line-clamp]") != null ||
            image?.attr("alt").isNullOrBlank().not()
        return image != null && title
    }

    private fun categoryType(url: String): TvType = when {
        url.contains("/anime-izle", ignoreCase = true) -> TvType.Anime
        url.contains("/kdrama-izle", ignoreCase = true) -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    private fun Element.diziler(type: TvType): SearchResponse? {
        val kart = this.closest(".prm-borderb, article, li, [class*=card]") ?: this.parent() ?: this
        val image = this.selectFirst("img") ?: kart.selectFirst("img")
        // A link's title attribute may contain the rating or accessibility text.
        // Prefer visible heading/title text, then meaningful image alt, then link title.
        val titleCandidates = listOf(
            kart.selectFirst("h2, h3, h4, [class*=title], [class*=line-clamp]")?.text(),
            image?.attr("alt"),
            this.attr("aria-label"),
            this.attr("title"),
            this.text()
        ).map { it.orEmpty().removeSuffix(" izle").trim() }
        val title = titleCandidates.firstOrNull {
            it.isNotBlank() && !it.matches(Regex("""\d+(?:[.,]\d+)?""")) &&
                !it.matches(Regex("""(?i)(imdb\s*)?\d+(?:[.,]\d+)?\s*/\s*10"""))
        }.orEmpty()
        if (title.isBlank()) return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(image?.let { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
                .asSequence()
                .map { img.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
        })
        val score = kart.selectFirst("[class*=score], h4")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name   = this.selectFirst("h3")?.text()?.trim() ?: return null
        val epName = this.selectFirst("h3 + div")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "")
        val href   = fixUrlNull(this.attr("href")) ?: return null

        return newTvSeriesSearchResponse(if (epName.isNullOrBlank()) name else "$name - $epName", href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(this@sonBolumler.selectFirst("img")?.let { img ->
                listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
                    .asSequence()
                    .map { img.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            })
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val yanit = app.post(
            "${mainUrl}/api/bg/searchContent",
            params  = mapOf("searchterm" to query),
            referer = "${mainUrl}/",
            headers = mapOf("Accept" to "application/json, text/plain, */*")
        ).parsedSafe<SifreliYanit>() ?: return emptyList()

        val veri = jsonOku<AramaSonuc>(coz(yanit.response ?: return emptyList()) ?: return emptyList()) ?: return emptyList()

        return veri.result.orEmpty().mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug  = item.slug ?: return@mapNotNull null

            newTvSeriesSearchResponse(title, fixUrl("/${slug.trimStart('/')}"), TvType.TvSeries) {
                this.posterUrl = resim(item.poster)
                this.year      = item.year
                this.score     = Score.from10(item.imdb)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var veri = sayfaVerisi(app.get(url, referer = "${mainUrl}/").document) ?: return null

        // Son bölümler listesinden gelen bölüm bağlantısını dizi sayfasına çevir
        if (veri.findedType == "Episodes") {
            val diziSlug = veri.related?.seriesDetail?.result?.slug ?: return null
            veri = sayfaVerisi(app.get(fixUrl("/${diziSlug.trimStart('/')}"), referer = "${mainUrl}/").document) ?: return null
        }

        val dizi        = veri.findedResult?.result ?: return null
        val title       = dizi.title ?: return null
        val diziUrl     = fixUrl("/${(dizi.slug ?: url.substringAfter(mainUrl)).trimStart('/')}")
        val tags        = veri.related?.categories?.result?.mapNotNull { it.name }
        val actors      = veri.related?.casts?.result?.mapNotNull { cast ->
            Actor(cast.name ?: return@mapNotNull null, resim(cast.image)) to cast.role
        }

        val episodes = veri.related?.seasons?.result.orEmpty()
            .flatMap { sezon ->
                sezon.episodes.orEmpty().mapNotNull { bolum ->
                    val slug = bolum.slug ?: return@mapNotNull null
                    newEpisode(fixUrl("/${slug.trimStart('/')}")) {
                        this.name = listOfNotNull(
                            bolum.subtitle?.takeIf { it != bolum.text },
                            bolum.language?.takeIf { it.contains("Dublaj", ignoreCase = true) }
                        ).joinToString(" - ").ifBlank { bolum.text }
                        this.season = bolum.season ?: sezon.season
                        this.episode = bolum.episode
                        this.description = bolum.description
                        this.addDate(bolum.releaseDate?.substringBefore("T"))
                    }
                }
            }
            // Do not rely on the website's JSON order: CloudStream's next-episode
            // navigation follows this list, so it must be stable by season/episode.
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.data }))

        return newTvSeriesLoadResponse(title, diziUrl, TvType.TvSeries, episodes) {
            this.posterUrl           = resim(dizi.poster)
            this.backgroundPosterUrl = resim(dizi.back)
            this.year                = dizi.year
            this.plot                = dizi.description
            this.tags                = tags
            this.duration            = dizi.minutes?.takeIf { it > 0 }
            this.score               = Score.from10(dizi.imdb)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZL", "Bölüm kaynakları okunuyor: " + data.substringBefore('?'))

        val veri = sayfaVerisi(app.get(data, referer = "${mainUrl}/").document) ?: run {
            Log.w("DZL", "Bölüm sayfasının yapılandırılmış verisi okunamadı.")
            return false
        }
        val kaynaklar = veri.related?.episodeSources?.result.orEmpty()
        val candidates = kaynaklar.flatMap { source ->
            SourceCandidateParser.extract(source.content, data).map { url -> source to url }
        }.distinctBy { it.second }

        if (candidates.isEmpty()) {
            Log.w("DZL", "Bölüm verisinde desteklenen bir URL/iframe adayı bulunamadı.")
            return false
        }

        var emittedLinks = 0
        val emitLink: (ExtractorLink) -> Unit = { link ->
            emittedLinks += 1
            callback(link)
        }

        candidates.forEach { (source, url) ->
            val label = listOfNotNull(
                source.name?.takeIf { it.isNotBlank() },
                source.language?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(" - ").ifBlank { "Dizilla" }

            Log.d("DZL", "Kaynak adayı: " + url.substringBefore('?'))
            runCatching {
                val path = url.substringBefore('?').substringBefore('#').lowercase()
                val directMedia = path.endsWith(".m3u8") ||
                    path.endsWith(".mp4") ||
                    path.endsWith(".webm") ||
                    path.endsWith(".mkv") ||
                    path.endsWith(".mpd")

                when {
                    directMedia -> {
                        val type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else null
                        emitLink(newExtractorLink(label, label, url, type) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        })
                    }
                    ContentXGenel.uygunMu(url) -> {
                        ContentXGenel().getUrl(url, data, subtitleCallback, emitLink)
                    }
                    else -> {
                        loadExtractor(url, data, subtitleCallback, emitLink)
                    }
                }
            }.onFailure { error ->
                Log.w("DZL", "Kaynak çözümlenemedi: " + url.substringBefore('?'), error)
            }
        }

        if (emittedLinks == 0) {
            Log.w("DZL", "Kaynak adayları bulundu ancak hiçbir oynatılabilir bağlantı çıkarılamadı.")
        }
        return emittedLinks > 0
    }

    // ! __NEXT_DATA__ içindeki "secureData" alanı AES-256-CBC ile şifrelenmiştir
    private fun sayfaVerisi(document: Document): SayfaSonuc? {
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val secure   = jsonOku<NextData>(nextData)?.props?.pageProps?.secureData ?: return null
        val cozulmus = coz(secure) ?: return null

        return jsonOku<SecureData>(cozulmus)?.content?.result
    }

    private fun coz(sifreli: String): String? {
        return runCatching {
            val hash   = MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            val anahtar = base64Encode(hash).substring(0, 32).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(anahtar, "AES"), IvParameterSpec(ByteArray(16)))
            String(cipher.doFinal(base64DecodeArray(sifreli)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun resim(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
    }

    // ? JSON modelleri
    data class SifreliYanit(@JsonProperty("response") val response: String? = null)

    data class AramaSonuc(@JsonProperty("result") val result: List<AramaItem>? = null)

    data class AramaItem(
        @JsonProperty("object_name")               val name: String?   = null,
        @JsonProperty("used_slug")                 val slug: String?   = null,
        @JsonProperty("object_release_year")       val year: Int?      = null,
        @JsonProperty("object_related_imdb_point") val imdb: Double?   = null,
        @JsonProperty("object_poster_url")         val poster: String? = null
    )

    data class NextData(@JsonProperty("props") val props: NextProps? = null)
    data class NextProps(@JsonProperty("pageProps") val pageProps: PageProps? = null)
    data class PageProps(@JsonProperty("secureData") val secureData: String? = null)

    data class SecureData(@JsonProperty("content") val content: SecureContent? = null)
    data class SecureContent(@JsonProperty("result") val result: SayfaSonuc? = null)

    data class SayfaSonuc(
        @JsonProperty("FindedType")     val findedType: String?       = null,
        @JsonProperty("FindedResult")   val findedResult: DiziSonuc?  = null,
        @JsonProperty("RelatedResults") val related: Related?         = null
    )

    data class DiziSonuc(@JsonProperty("result") val result: DiziDetay? = null)

    data class DiziDetay(
        @JsonProperty("original_title") val title: String?       = null,
        @JsonProperty("description")    val description: String? = null,
        @JsonProperty("release_year")   val year: Int?           = null,
        @JsonProperty("imdb_point")     val imdb: Double?        = null,
        @JsonProperty("total_minutes")  val minutes: Int?        = null,
        @JsonProperty("poster_url")     val poster: String?      = null,
        @JsonProperty("back_url")       val back: String?        = null,
        @JsonProperty("used_slug")      val slug: String?        = null
    )

    data class Related(
        @JsonProperty("getSerieCastsById")         val casts: CastListe?          = null,
        @JsonProperty("getSerieCategoriesById")    val categories: KategoriListe? = null,
        @JsonProperty("getSerieSeasonAndEpisodes") val seasons: SezonListe?       = null,
        @JsonProperty("getEpisodeSources")         val episodeSources: KaynakListe? = null,
        @JsonProperty("getSeriesDetail")           val seriesDetail: DiziSonuc?   = null
    )

    data class CastListe(@JsonProperty("result") val result: List<Cast>? = null)
    data class Cast(
        @JsonProperty("name")       val name: String?  = null,
        @JsonProperty("cast_image") val image: String? = null,
        @JsonProperty("role_name")  val role: String?  = null
    )

    data class KategoriListe(@JsonProperty("result") val result: List<Kategori>? = null)
    data class Kategori(@JsonProperty("name") val name: String? = null)

    data class SezonListe(@JsonProperty("result") val result: List<Sezon>? = null)
    data class Sezon(
        @JsonProperty("season_no") val season: Int?             = null,
        @JsonProperty("episodes")  val episodes: List<Bolum>?   = null
    )
    data class Bolum(
        @JsonProperty("season_no")             val season: Int?         = null,
        @JsonProperty("episode_no")            val episode: Int?        = null,
        @JsonProperty("episode_text")          val text: String?        = null,
        @JsonProperty("episode_subtitle")      val subtitle: String?    = null,
        @JsonProperty("episode_description")   val description: String? = null,
        @JsonProperty("episode_language_name") val language: String?    = null,
        @JsonProperty("release_date")          val releaseDate: String? = null,
        @JsonProperty("used_slug")             val slug: String?        = null
    )

    data class KaynakListe(@JsonProperty("result") val result: List<Kaynak>? = null)
    data class Kaynak(
        @JsonProperty("source_name")    val name: String?     = null,
        @JsonProperty("source_content") val content: String?  = null,
        @JsonProperty("language_name")  val language: String? = null
    )
}

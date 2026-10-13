# DiziYou — Site Discovery Profile

**Status:** Parser/catalogue repair and version 2 package built; CI passed on code commit `f23c7de065278ce6ad22463a93510997bc7f6dc3`  
**Site:** https://www.diziyou.one/  
**Profile date:** 2026-10-13  
**Changed provider:** `DiziYou/` only  
**Live playback:** NOT YET VERIFIED on a real CloudStream client  
**Live raw HTML:** Direct fetch from this research environment returned HTTP 502; this does not establish that the public site is offline.

## Comparative research

Reviewed multiple public implementations rather than assuming a single old provider was current:

- [safarxe/CloudStream — DiziYou.kt](https://github.com/safarxe/CloudStream/blob/main/DiziYou/src/main/kotlin/com/keyiflerolsun/DiziYou.kt)
- [feroxx/Kekik-cloudstream — DiziYou.kt](https://github.com/feroxx/Kekik-cloudstream/blob/master/DiziYou/src/main/kotlin/com/keyiflerolsun/DiziYou.kt)
- [Emre-Kahveci/CloudStreamHub — DiziYou.kt](https://github.com/Emre-Kahveci/CloudStreamHub/blob/main/DiziYou/src/main/kotlin/com/cloudstream/tr/diziyou/DiziYou.kt)
- [Saloo1575/SalooRepo — DiziYou.kt](https://github.com/Saloo1575/SalooRepo/blob/main/DiziYou/src/main/kotlin/com/saloo/sites/diziyou/DiziYou.kt)
- [falsisdev/anthology — DiziYou provider](https://github.com/falsisdev/anthology/blob/main/src/diziyou/index.js)
- [caca1403/cine-pulse — DiziYou provider](https://github.com/caca1403/cine-pulse/blob/main/api/providers/diziyou.py)
- [DTH-GLOBAL/bot — DiziYou scraper](https://github.com/DTH-GLOBAL/bot/blob/main/diziyou.py)

Repositories containing copies or older variants were treated as corroborating evidence, not as proof of current live behaviour.

## What the implementations have in common

**Catalogue and search.** The current canonical domain referenced by these implementations is `https://www.diziyou.one`. Archive examples include:

- `/dizi-arsivi/?filtrele=tarih&sirala=DESC`
- `/dizi-arsivi/?filtrele=alfabetik&sirala=ASC`
- `/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7`
- Search uses `/?s={query}`.

**Series versus episode URLs.** Episode slugs repeatedly follow `-{season}-sezon-{episode}-bolum`. Treating an episode URL as a series detail URL causes a detail page to show the episode title and navigation/related links instead of the real series episode list.

**Player chain.** Multiple independent implementations inspect `iframe#diziyouPlayer`, commonly pointing to `/player/{id}.html`. One useful additional detail from another scraper is the `#diziyouSource` element on the player page, whose source attribute can expose the actual media URL. The Kotlin implementation now checks the player page for exposed source elements and ordinary or JS-escaped `.m3u8`, `.mp4`, and `.mpd` URLs before using the known storage-layout fallback.

**Fallback media layout.** Public implementations commonly construct `https://storage.diziyou.one/episodes/{id}/play.m3u8`, and some also construct `episodes/{id}_tr/play.m3u8` and VTT subtitle URLs. These patterns are corroborated by public code, but the current endpoint response codes, manifest contents, required headers, and subtitle payloads were not live-verified here. They are compatibility fallbacks, not a claim that every episode has every language variant.

## Fixes applied to our DiziYou module

- Replaced the unsupported `/genel/` archive tab with archive URLs found in the comparable implementations.
- Serialized main-page requests with a short delay, consistent with an older public DiziYou implementation's bot/rate-protection precautions.
- Stopped treating the `#list-series` wrapper as a single card. Extraction now visits individual anchors/cards and deduplicates by URL.
- Filtered out archive, pagination, category, tag, and episode paths where a series page is expected.
- Episode cards map to the parent series URL only when the season/episode slug matches the known pattern.
- Series names are taken from series-title elements or the series slug; poster alt text such as “İngilizce Altyazılı” and “Türkçe Dublaj” is not accepted as a series title.
- Opening an episode URL first resolves the parent series page. Episode extraction filters links by the same parent series path and derives season/episode numbers from the URL before using visible labels. Generic navigation captions such as “Son Eklenen Bölümler” and “Önceki Bölüm” are not exposed as episode names.
- Player resolution continues to prefer media actually present in player HTML; the known storage pattern remains a fallback when the player ID is found.
- Added regression tests for slug-to-series mapping, a non-`www` episode hostname, title generation from an episode slug, and the `#diziyouSource` HLS source element.
- No other provider module is changed. The pull request contains the DiziYou provider, its version manifest (`DiziYou/build.gradle.kts`), its parser tests, and this DiziYou-specific profile.

## Verification matrix

| Capability | Status | Evidence / limitation |
|---|---|---|
| Kotlin compile | PASS | GitHub Actions run #468; provider tests and package build passed |
| Parser regression tests | PASS | GitHub Actions run #468; slug mapping, titles, lazy iframe, plain/escaped HLS and source element fixtures |
| Plugin package / repository validation | PASS | GitHub Actions run #468 built `.cs3`, generated the plugin index, validated the repository, and uploaded the build artifact; publish job was correctly skipped for a PR |
| Catalogue on a real CloudStream client | NOT TESTED | Live browser HTML unavailable in this environment |
| Search accuracy | NOT TESTED against live responses | Current raw search response unavailable |
| Series page / season / episode order | Parser repaired, still needs live client test | Slug-filtered fixture logic; no real device/session |
| Player iframe and native media | PARTLY TESTED with fixtures | Actual live iframe and manifest response unavailable |
| Video playback | NOT VERIFIED | No client playback session |
| Turkish / English subtitles | NOT VERIFIED | Option markers and URL patterns supported; actual VTT responses unverified |
| Pagination | Implemented from known URL pattern, live-unverified | No current raw paginated response |

## Remaining evidence needed

A successful build is necessary but does not establish that the live site is playable. Full acceptance still requires a working public response and a CloudStream client session to verify the home catalogue, search, series detail, episode order, the real player iframe/source, the HLS manifest, and each subtitle track actually advertised by that episode.

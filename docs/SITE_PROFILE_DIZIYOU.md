# DiziYou — Site Discovery Profile

**Status:** Revision 4 source fixes implemented; CI validation pending.  
**Site:** https://www.diziyou.one/  
**Profile date:** 2026-10-13  
**Changed provider:** `DiziYou/` only  
**Live client evidence:** User confirmed v3 plays, but reports quality limited to 720p, irrelevant search results, missing posters, and wants separate language/source and quality controls. Revision 4 switches back to the canonical HLS master playlist; device retest is pending.  
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

**Player chain.** Multiple independent implementations inspect `iframe#diziyouPlayer`, commonly pointing to `/player/{id}.html`. The page's selected `#diziyouSource` URL can be a single quality rendition; passing that child URL directly may restrict the player to (for example) 720p. Revision 4 therefore supplies the known HLS master URL `https://storage.diziyou.one/episodes/{id}/play.m3u8` when a player ID is available, and the `_tr/play.m3u8` master for an explicitly declared Turkish-dub option. A master playlist can expose all declared resolutions and alternate audio renditions to ExoPlayer; actual current manifest contents still require a live test.

**Fallback media layout.** Public implementations commonly construct `https://storage.diziyou.one/episodes/{id}/play.m3u8`, and some also construct `episodes/{id}_tr/play.m3u8` and VTT subtitle URLs. These patterns are corroborated by public code, but the current endpoint response codes, manifest contents, required headers, and subtitle payloads were not live-verified here. They are compatibility fallbacks, not a claim that every episode has every language variant.

## Fixes applied to our DiziYou module

- Replaced the unsupported `/genel/` archive tab with archive URLs found in the comparable implementations.
- Serialized main-page requests with a short delay, consistent with an older public DiziYou implementation's bot/rate-protection precautions.
- Stopped treating the `#list-series` wrapper as a single card. Extraction visits individual cards/anchors so a wrapper's first image cannot be assigned to unrelated search results.
- Search terms are normalized for Turkish diacritics and unrelated rows are rejected unless all meaningful query terms match the title or slug. Page 2 is only requested when page 1 has no relevant matches.
- Poster metadata repair is bounded to 24 missing entries per page/search response rather than stopping after eight; actual poster fields are preferred over generic responsive-picture candidates.
- Filtered out archive, pagination, category, tag, and episode paths where a series page is expected.
- Episode cards map to the parent series URL only when the season/episode slug matches the known pattern.
- Series names are taken from series-title elements or the series slug; poster alt text such as “İngilizce Altyazılı” and “Türkçe Dublaj” is not accepted as a series title.
- Poster resolution checks poster-specific containers before arbitrary card images, considers lazy-load attributes and `srcset`, prefers the largest declared `srcset` candidate, and rejects obvious placeholder/flag artwork. Detail pages also check Open Graph poster metadata before image fallbacks.
- Opening an episode URL first resolves the parent series page. Episode extraction filters links by the same parent series path and derives season/episode numbers from the URL before using visible labels. Generic navigation captions such as “Son Eklenen Bölümler” and “Önceki Bölüm” are not exposed as episode names.
- Episode labels containing only site-internal file names such as `eps1.0_hellofriend.mov` are normalized to `1. Sezon 1. Bölüm`; a readable episode title is preserved after the normalized season/episode prefix when available.
- Player resolution emits the canonical HLS master `play.m3u8` for original-language viewing, rather than the explicit child rendition picked by the website player's page. This is the important difference for 1080p/720p/480p selection: the player needs the master playlist, not a URL pinned to one rendition.
- Original-language and explicitly declared Turkish-dub playback are exposed as separate source entries. Subtitle options are advertised only when the corresponding option element actually exists in the episode HTML; mere mentions in script text no longer create phantom tracks. If the HLS master publishes alternate audio groups, the player can expose those as audio tracks too.
- Quality is not assigned a hardcoded 720p cap. The plugin leaves quality unknown on a master HLS link so the player reads the playlist's available variants. User reports the site has 1080p; the live master manifest could not be fetched from this environment, so availability and the exact audio group list remain to be confirmed on-device.
- Added regression tests for slug-to-series mapping, a non-`www` episode hostname, title generation from an episode slug, and the `#diziyouSource` HLS source element.
- No other provider module is changed. The pull request contains the DiziYou provider, its version manifest (`DiziYou/build.gradle.kts`), its parser tests, and this DiziYou-specific profile.

## Verification matrix

| Capability | Status | Evidence / limitation |
|---|---|---|
| Kotlin compile | PASS | GitHub Actions run #468; provider tests and package build passed |
| Parser regression tests | Pending revision 4 CI | Adds master-playlist URL, strict option detection, query matching and Turkish normalization fixtures |
| Plugin package / repository validation | PASS | GitHub Actions run #468 built `.cs3`, generated the plugin index, validated the repository, and uploaded the build artifact; publish job was correctly skipped for a PR |
| Catalogue on a real CloudStream client | NOT TESTED | Live browser HTML unavailable in this environment |
| Search accuracy | Logic covered by tests; live response still unavailable | Query/title matching and Turkish diacritic normalization fixtures added |
| Series page / season / episode order | PARTLY CONFIRMED on v2 | User screenshot shows episode listing, but file-like labels need cleanup in v3 |
| Player iframe and native media | PARTLY CONFIRMED on v3; v4 master URL needs device retest | v4 no longer passes the player's selected child rendition when item ID is available |
| Video playback | PARTLY CONFIRMED on v3; v4 quality/audio tracks need device retest | Canonical HLS master chosen to reveal all manifest renditions |
| Turkish / English subtitles | NOT VERIFIED | Option markers and URL patterns supported; actual VTT responses unverified |
| Pagination | Implemented from known URL pattern, live-unverified | No current raw paginated response |

## Remaining evidence needed

A successful build is necessary but does not establish that the live site is playable. Full acceptance still requires a working public response and a CloudStream client session to verify the home catalogue, search, series detail, episode order, the real player iframe/source, the HLS manifest, and each subtitle track actually advertised by that episode.

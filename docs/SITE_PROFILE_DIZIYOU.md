# DiziYou — Site Discovery Profile

**Status:** COMPARATIVE REVIEW APPLIED; LIVE CONTRACT PARTLY BLOCKED  
**Site:** https://www.diziyou.one/  
**Profile date:** 2026-10-13  
**Provider implementation:** CREATED in `DiziYou/`  
**Previous baseline build/package:** PASS in GitHub Actions run 38271563419 (commit 1f8398ca4a9858a3b50de2c39e1855ee5e66bcc5)  
**Current comparison branch CI:** PENDING for latest changes  
**CloudStream playback:** NOT TESTED  
**Live raw HTML/player contract:** BLOCKED in this research environment (502 on direct fetch)

## Evidence and current scope

Search-indexed public pages expose the current homepage, series detail pages, episode detail pages, season/episode labels, and the labels “Türkçe Altyazılı”, “Türkçe Dublaj”, and “İngilizce Altyazılı”. Examples:
- Homepage: https://www.diziyou.one/
- Series detail: https://www.diziyou.one/silo/
- Episode detail: https://www.diziyou.one/sold-out-on-you-1-sezon-1-bolum/
- Another episode page showing three language choices: https://www.diziyou.one/all-of-us-are-dead-1-sezon-1-bolum/

These indexed results confirm visible catalogue and episode information, but they do not provide the current raw DOM, iframe src, player config, media URL, or subtitle payload. Direct fetch from this research environment returned 502, and a local fetch attempt could not resolve DNS. This is an environment access block, not evidence the site is offline.

## Provider implementation

The current Kotlin module implements a conservative HTML-based catalogue/search/detail adapter and delegates discovered iframe/source URLs to CloudStream's standard extractor. Episode labels parse both season and episode numbers from text such as `2. Sezon 3. Bölüm`. Latest episode cards are mapped back to a parent series only when the slug matches the site's explicit season/episode pattern.

The implementation still uses broad fallback selectors because the live raw DOM was not retrievable. These selectors are hypotheses/fallbacks, not confirmed site contracts.

## Cross-repository comparison (2026-10-13)

Reviewed public implementations:
- [Emre-Kahveci/CloudStreamHub](https://github.com/Emre-Kahveci/CloudStreamHub), Kotlin CloudStream provider.
- [Saloo1575/SalooRepo](https://github.com/Saloo1575/SalooRepo), Kotlin CloudStream provider with a player-page HLS lookup.
- [falsisdev/anthology](https://github.com/falsisdev/anthology), JavaScript provider using the same site and storage host.
- [UmayTrade/MyTV](https://github.com/UmayTrade/MyTV), [burak-ayd/cloudstream-repo](https://github.com/burak-ayd/cloudstream-repo), [21nek/nektv](https://github.com/21nek/nektv), [Kayracs3/Kayra](https://github.com/Kayracs3/Kayra), and [Barisakd2163/Xelo-Repo](https://github.com/Barisakd2163/Xelo-Repo), additional public Kotlin providers/forks found by GitHub code search. Several use older or alternate DiziYou domains, so their domain/player assumptions were not treated as current truth.

Shared implementation signals across these references:
- Episode pages use `iframe#diziyouPlayer` and the iframe commonly identifies a `/player/{id}.html` page.
- Multiple independent implementations look for HLS under `storage.diziyou.one/episodes/{id}/play.m3u8`.
- Some references also construct a Turkish-dub candidate under `episodes/{id}_tr/play.m3u8` and subtitle candidates under `subtitles/{id}/tr.vtt` and `en.vtt`.

These are corroborated prior-art patterns, **not live-verified current endpoints**. The provider now prefers media URLs actually exposed by the player HTML, and keeps the storage URL layout only as a compatibility fallback. Subtitle and dub options are advertised only when matching option markers are found in the episode page. The site’s present raw HTML, HTTP response codes, manifest contents, and subtitle payloads remain unverified here.

### Changes in the comparison branch

- Latest-episode cards are mapped back to a series URL only when the URL matches the explicit `-{season}-sezon-{episode}-bolum` pattern; this prevents episode links from being mistaken for series pages.
- Player iframe URL is resolved from `src` or lazy `data-src`, even if the iframe uses a different host/path than expected.
- Source parsing is factored into top-level helpers so parser unit tests do not instantiate `MainAPI`; relative iframe URLs resolve against the document base URI.
- The storage HLS fallback is keyed off a discovered player URL rather than the literal `diziyouPlayer` text appearing in HTML.
- Player HTML is inspected for ordinary and JavaScript-escaped `.m3u8`, `.mp4`, and `.mpd` URLs before using a constructed fallback.
- Regression tests cover plain/escaped HLS URL extraction and episode-to-series URL mapping.
- Language variants remain available when a native HLS URL is also found.
- No other provider module is changed.

## Verification matrix

| Capability | Status | Evidence |
|---|---|---|
| Kotlin compile / plugin package | PENDING for current branch | Previous baseline passed in Actions run 38271563419; current branch run queued |
| Existing module/unit tests before this change | PASS | Actions run 38271563419 |
| Season/episode parser regression tests | Added/CI pending | Current branch covers escaped HLS URLs, relative iframe URLs, and episode-to-series mapping without provider instantiation |
| Homepage catalogue in live CloudStream | NOT TESTED | No real device/session |
| Search accuracy | NOT TESTED | Raw response inaccessible here |
| Detail metadata and episode mapping | PARTLY TESTED | Indexed text evidence only; no raw DOM fixture |
| Player iframe extraction | PARTLY TESTED | Unit fixture covers lazy data-src; live iframe remains unverified |
| Video playback | NOT TESTED | No stream/manifest response or device test |
| Turkish subtitles | NOT TESTED | Labels visible in index, actual subtitle URL/payload unverified |
| English subtitles | NOT TESTED | Labels visible in index, actual subtitle URL/payload unverified |
| Pagination | NOT TESTED | No verified pagination request/response |

## Historical prior-art — not a current contract

An older public CloudStream provider in `feroxx/Kekik-cloudstream` used selectors including `div#list-series-main`, `div.incontent div#list-series`, `div.bolumust`, `iframe#diziyouPlayer`, and `span.diziyouOption`. It also constructed candidate media/subtitle URLs under a `storage` host. These are historical leads only; the current site's live DOM and media responses have not been verified. Do not copy these selectors or URL patterns into production without new evidence and a license/attribution review.

## Next required validation

1. Retry public access from a normal browser or an environment that can fetch the site's raw HTML.
2. Save sanitized samples for homepage, series detail, episode page, iframe response and player config. Do not include cookies, tokens, or signed media URLs.
3. Verify the actual iframe chain, media response, required headers, and subtitle files separately.
4. Run the provider in CloudStream and test homepage, search, detail, season/episode order, direct episode opening, next episode, source switching, video playback, and each advertised subtitle track.
5. Publish an updated artifact only after the checks and explicit manual approval.

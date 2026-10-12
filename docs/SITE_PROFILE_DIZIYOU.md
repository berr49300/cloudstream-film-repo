# DiziYou — Site Discovery Profile

**Status:** IMPLEMENTATION BUILDS; LIVE CONTRACT PARTLY BLOCKED  
**Site:** https://www.diziyou.one/  
**Profile date:** 2026-10-12  
**Provider implementation:** CREATED in `DiziYou/`  
**Build/package:** PASS in GitHub Actions run 38271563419 (commit 1f8398ca4a9858a3b50de2c39e1855ee5e66bcc5)  
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

The current Kotlin module implements a conservative HTML-based catalogue/search/detail adapter and delegates discovered iframe/source URLs to CloudStream's standard extractor. Episode labels now parse both season and episode numbers from text such as `2. Sezon 3. Bölüm`.

The implementation still uses broad fallback selectors because the live raw DOM was not retrievable. These selectors are hypotheses/fallbacks, not confirmed site contracts. The implementation does **not** hardcode the historical third-party provider's guessed storage HLS or subtitle URL patterns.

## Verification matrix

| Capability | Status | Evidence |
|---|---|---|
| Kotlin compile / plugin package | PASS | Actions run 38271563419 |
| Existing module/unit tests before this change | PASS | Actions run 38271563419 |
| Season/episode parser regression tests | Pending | New CI run after this commit |
| Homepage catalogue in live CloudStream | NOT TESTED | No real device/session |
| Search accuracy | NOT TESTED | Raw response inaccessible here |
| Detail metadata and episode mapping | PARTLY TESTED | Indexed text evidence only; no raw DOM fixture |
| Player iframe extraction | NOT TESTED | iframe URL/host not exposed by index |
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

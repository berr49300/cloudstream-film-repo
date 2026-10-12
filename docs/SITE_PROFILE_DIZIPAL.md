# Site Profile — DiziPal (Haty beta)

- **Last reviewed:** 2026-10-12
- **Provider module:** HatyDiziPal
- **Overall status:** INVESTIGATING / beta
- **Playback status:** NOT TESTED on a real CloudStream device.
- **Source evidence:** THY TV APK class inventory (DizipalRepository and DizipalRmK); public CloudStreamHub provider code is secondary evidence, not proof of identical APK runtime behavior.

## Evidence ledger

| Claim | Status | Evidence | Remaining gap |
|---|---|---|---|
| Candidate host is https://dizipal1430.com | HYPOTHESIS / public-code evidence | Maintained public CloudStream provider lists this host | Fresh raw HTTP response not validated here |
| Catalog contains /dizi/ and /film/ links | HYPOTHESIS | Public provider parser targets these route families | Verify current raw DOM |
| Search API is GET /api/search.php?q=... | HYPOTHESIS | Public provider code calls it and expects results[].title,type,slug,poster | Verify actual status and JSON response |
| Episodes use /bolum/ routes | HYPOTHESIS | Public parser targets these route families | Validate season/special episode cases |
| Player exposes videoplay.vip iframe via encodedContent | HYPOTHESIS | Public provider decodes a page-provided base64 literal | Verify fresh episode HTML and ordinary embed response |
| Player has src and optional tracksData.subtitles | HYPOTHESIS | Public provider reads these fields | Verify live manifest and subtitle content |
| APK DizipalRmK uses same player protocol | UNKNOWN | Static APK report indicates separate resolver/crypto helper; equivalence is unproven | Method-level decompilation needed |

## Request map

| Action | Route/pattern | Method | Status |
|---|---|---|---|
| Catalog | /, /diziler, /filmler | GET | HYPOTHESIS |
| Search | /api/search.php?q={query} | GET | HYPOTHESIS |
| Detail | /dizi/{slug}, /film/{slug} | GET | HYPOTHESIS |
| Episode | /bolum/{slug} | GET | HYPOTHESIS |
| Player | page-provided iframe; public reference observes videoplay.vip | GET | HYPOTHESIS |
| Manifest | page-provided src (typically HLS) | GET | NOT TESTED |
| Subtitles | player-provided tracksData.subtitles | GET or player query URL | NOT TESTED |

## Implementation boundaries

- Site code is an independent module. Existing FilmMakinesi, Dizilla, FullHDFilmizlesene and DDizi providers are untouched.
- Search parsing is defensive; changed/missing fields return an empty list rather than guessed items.
- Player candidates must be explicit in the page/player config. The videoplay.vip subtitle query form is based on public implementation evidence and remains unverified against a live response.
- No credentials, active signed media URLs or private config are committed; no CAPTCHA/DRM/access-control bypass is attempted.

## Test record

| Test | Result |
|---|---|
| Same-host catalog parsing and movie/series typing | Fixture unit tests added; awaiting CI |
| Episode host filtering and season/episode mapping | Fixture unit tests added; awaiting CI |
| Live catalog/search/detail/player HTTP behavior | NOT TESTED |
| Video/audio playback and subtitle rendering | NOT TESTED |

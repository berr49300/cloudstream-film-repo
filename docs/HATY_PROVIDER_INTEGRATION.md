# Haty — unified application provider integration

## Goal

Haty is the working name for the complete multi-provider project. The goal is to bring every identifiable source family from the inspected APK into this repository and its single CloudStream repo feed. This is a project-wide inventory and integration effort, not a Turkish123-only plugin.

The repo/feed is unified; provider adapters remain separate Gradle modules because each site/service has its own catalogue, API, player and access rules. The user-facing experience can be unified by installing the providers from the same feed.

## Full source inventory

The supplied APK, previously identified during static inspection as THY TV 5.0, was not run. Class/string names below are evidence of candidate families, not proof that current sites work or that playback is compatible.

| Provider family | Current repo status | Haty integration action |
|---|---|---|
| FilmMakinesi | Existing provider | Preserve; do not duplicate or modify without a separate request |
| Dizilla | Existing provider | Preserve; regression-test only when shared infrastructure changes |
| FullHDFilmizlesene | Existing provider | Preserve; regression-test only when shared infrastructure changes |
| DDizi | Existing provider | Preserve |
| Dizipal | Public site family exists; current canonical domain is unstable/uncertain | Catalogue examples found in public provider code, but domain and current player must be revalidated before implementation |
| Diziyou | Public catalogue page observed at https://diziyou.net/; another public domain https://www.diziyou.one/ also surfaced | Catalogue exists; exact raw selectors, episode mapping and player chain still require verification |
| Turkish123 | Candidate found in APK inventory | Verify raw HTML, search, episodes, source chain and subtitles before implementing |
| Canlikolik | Candidate live-TV provider | Verify current public channel list and permitted playback metadata |
| tabii | Candidate service provider; public URL observed: https://www.tabii.com/ | Verify official public catalogue and supported playback interface |
| CanlıTribün | Candidate sports provider | Resolve the remote base URL from public, permitted evidence; static APK strings were insufficient |
| Papaz | Candidate sports provider | Resolve the remote base URL from public, permitted evidence; static APK strings were insufficient |
| Generic media types | APK strings include .m3u8, .mpd, .mp4, .m3u, .ts and .flv | Add format handling only where actual provider/source evidence supports it |

## Implementation status — 2026-10-12

- [x] Repository modules are auto-discovered by `settings.gradle.kts` when a directory contains `build.gradle.kts`; no hard-coded include list is needed.
- [x] Confirmed Turkish123 public series catalogue HTML is rendered with linked series entries; public page inspection alone does not confirm raw selectors or playback.
- [x] Turkish123 initial beta provider module exists and is included in the unified repo feed build.
- [ ] Dizipal and Diziyou: public discovery evidence collected; provider modules are not yet implemented because the current source/player behavior is not verified.
- [ ] Search, detail, episode mapping, source resolution and subtitles: not yet runtime verified.
- [ ] APK remote domains for CanlıTribün and Papaz: unresolved; do not guess.

## Implementation order

1. **Inventory and build wiring:** confirm every module already in the repo, avoid duplicates, identify shared helper boundaries and keep one feed.
2. **Dizipal and Diziyou:** investigate public routes and raw responses; implement independent modules once selectors and player chains are evidenced.
3. **Turkish123:** investigate raw HTML, search/pagination, episode mapping, player/source chain and subtitles; then implement and add sanitized fixture tests.
4. **Live TV:** investigate Canlikolik and tabii independently. Do not treat a website as a channel list or assume every stream is compatible.
5. **Sports:** investigate CanlıTribün and Papaz base URLs and public response formats. Keep sports providers isolated from series/film providers.
6. **Integration and acceptance:** run unit tests, Gradle build, plugin index generation and repo-feed checks. Verify runtime playback/subtitles separately on CloudStream before marking them passed.

The order may change if a provider is blocked or a better-supported public interface is found. No endpoints, selectors or media URLs will be guessed.

## Turkish123 reconnaissance — 2026-10-12

Public pages observed:
- Home: https://ahs.turkish123.com/
- Series list: https://ahs.turkish123.com/series-list/
- Example detail: https://ahs.turkish123.com/anne-yarisi/

Confirmed from rendered public page output:
- Navigation includes Home, Series, Episodes, Calendar and Genre.
- The series list contains links to individual series pages.
- The example detail page exposes title, synopsis, genre, cast, duration, quality, year, status and episode/content links.
- A YouTube trailer iframe is present.

Not confirmed:
- Raw HTML selectors and embedded data shapes.
- Search request and pagination behavior.
- Episode-to-player request chain, playable source formats, required headers and subtitle behavior.
- Real CloudStream playback.

## Implementation and provenance policy

1. Each independent provider remains a separate module; all modules are distributed from the same repo feed.
2. Haty is the project name, not a claim that the original APK or third-party services were authored by this project. Do not falsify provenance or remove legally required notices.
3. Do not copy a third-party app wholesale or reuse private keys, tokens, cookies or signed media URLs. Implement from independently verified public behavior and preserve license/attribution for any code lawfully reused.
4. Do not bypass authentication, CAPTCHA, DRM, certificate pinning or other access controls.
5. Keep existing protected providers untouched unless separately requested.
6. Tests/builds must report PASS, FAIL, BLOCKED or NOT TESTED accurately. Build success alone does not prove playback or subtitle rendering.
7. Do not publish to `builds` or merge to `main` without explicit approval.

## Current status

- Repository contribution rules and existing module conventions reviewed.
- Static APK source inventory recorded.
- Turkish123 public home, series list and one detail page inspected; initial beta module committed.
- Diziyou public catalogue pages discovered; raw selectors and player chain still unverified.
- Dizipal public provider examples were found in a third-party repository, but they are not copied; its active domain and player chain need independent validation.
- Official tabii public catalogue and live-channel listings are visible, but a supported playback interface has not been verified. No authentication, geo-restriction, or DRM bypass will be attempted.
- Canlikolik, CanlıTribün and Papaz: no verified source/response chain yet.
- CloudStream playback/subtitles: NOT TESTED.
- Release branch `builds`: unchanged.


## Implementation progress — 2026-10-12

- **Implemented (initial beta):** `HatyTurkish123` module added under `HatyTurkish123/`; Gradle auto-discovery applies because the module has its own `build.gradle.kts`.
- **Catalog behavior:** reads the observed public home and series-list pages, extracts same-site series links from page HTML, and loads episode links only when their URLs visibly indicate episode/season/part paths.
- **Playback behavior:** delegates only explicitly embedded iframe/video/source URLs to CloudStream's extractor loader. No player endpoint or signed stream URL is hard-coded.
- **Known gaps:** search is intentionally disabled; pagination is intentionally limited to the first page; episode-link conventions and extractor/subtitle playback are not yet verified against live responses; no Android device playback test has been run.
- **Not yet implemented:** Dizipal, Diziyou, Canlikolik, tabii, CanlıTribün and Papaz. Their endpoints/player paths must be independently verified before adding provider code. Existing FilmMakinesi, Dizilla, FullHDFilmizlesene and DDizi modules remain untouched.
- **Validation status:** GitHub file commits succeeded. A Gradle build/test result is not yet available from this editing session, so this module must be treated as beta/unverified, not as a completed release.


## Additional public reconnaissance — 2026-10-12

### Diziyou
- Public catalogue evidence: https://diziyou.net/ and https://www.diziyou.one/hakkimizda/
- The pages expose catalogue/series entries and recent episodes in rendered public output.
- Not yet verified from raw responses: stable canonical host, HTML selectors, pagination/search request format, episode detail schema, media-source chain, subtitles, and CloudStream playback.
- Status: **RECONNAISSANCE ONLY**; do not ship a guessed parser.

### Dizipal
- Public evidence indicates the domain family changes frequently; a third-party CloudStream repository contains a historical adapter targeting a numbered domain, but that is not proof that the domain or implementation is currently valid.
- No third-party implementation has been copied into Haty.
- Status: **BLOCKED pending current-domain and public player verification**.

### tabii
- Official public page: https://www.tabii.com/tr
- The official page lists series, films, documentaries, kids content, and TRT live channels.
- A rendered public catalogue does not establish a supported unauthenticated playback API. Playback may be subject to account, region, or DRM rules.
- Status: **PUBLIC CATALOGUE CONFIRMED; PLAYBACK INTERFACE NOT VERIFIED**. Do not bypass access controls.

### Remaining sources
- Canlikolik: current public channel list and source metadata not yet verified.
- CanlıTribün and Papaz: base domains still unresolved from verified evidence; no domain guesses or fabricated stream URLs.

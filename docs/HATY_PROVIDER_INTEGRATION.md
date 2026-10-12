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
| DiziPal | HatyDiziPal beta at https://dizipalorjinal14.com after redirect from dizipal1430.com | Search/catalog/detail/episode and explicit player parsing implemented; playback unverified |
| DiziYou | HatyDiziYou beta at https://diziyou.net/ | HTML-linked series/episodes parsed; observed player embed returned 403 in research environment |
| Turkish123 | HatyTurkish123 beta | HTML catalogue/detail/episode parsing exists; search, player and subtitles remain unverified |
| Canlikolik | HatyCanlikolik beta; public candidates canlikolik5.my / canlikolik11.live | Rotating host family is public evidence; APK remote config and playback unverified |
| tabii | Candidate service provider; public URL observed: https://www.tabii.com/ | Verify official public catalogue and supported playback interface |
| CanlıTribün | HatyCanliTribun beta using public candidate canlitribun938.top | Exact APK config and player chain remain unverified |
| Papaz | HatyPapaz beta; public candidates papazsports1022.pro / 1023.pro | Channel/match catalogue appears in public output; playback source unverified |
| Generic media types | APK strings include .m3u8, .mpd, .mp4, .m3u, .ts and .flv | Add format handling only where actual provider/source evidence supports it |

## Implementation status — 2026-10-12

- [x] Repository modules are auto-discovered by `settings.gradle.kts` when a directory contains `build.gradle.kts`; no hard-coded include list is needed.
- [x] Confirmed Turkish123 public series catalogue HTML is rendered with linked series entries; public page inspection alone does not confirm raw selectors or playback.
- [x] Turkish123 initial beta provider module exists and is included in the unified repo feed build.
- [x] Dizipal: independent beta module added using public route/player patterns; fresh live responses and playback remain unverified.
- [x] tabii: separate beta live-provider module added, using the official live-page route and only explicit source URLs returned by the page; playback is unverified.
- [x] DiziYou: separate beta module added under `HatyDiziYou/`, using only current-host links found in returned HTML and delegating visible embeds to CloudStream extractors. Search/pagination are disabled and playback remains unverified because the observed embed returned HTTP 403.
- [ ] Search, detail, episode mapping, source resolution and subtitles: not yet runtime verified.
- [x] Papaz: beta HTML adapter updated to public mirror candidates papazsports1022.pro / 1023.pro; APK active base remains unverified.
- [x] Canlikolik: independent HTML-based beta module added with rotating public host candidates; APK active remote config and source responses remain unverified.
- [x] CanlıTribün: independent HTML-based beta module added for publicly found canlitribun938.top route family; playable source chain remains unverified.

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
- **Independent beta modules present:** HatyTurkish123, HatyDiziYou, HatyDiziPal, HatyTabii, HatyPapaz, HatyCanlikolik and HatyCanliTribun. Existing FilmMakinesi, Dizilla, FullHDFilmizlesene and DDizi modules remain untouched. Canlikolik and CanlıTribün are not implemented because the APK's active remote domains/player chains are not verified.
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


### Diziyou follow-up — 2026-10-12
- Current public series detail route observed: `https://diziyou.net/dizi/from/`; rendered output shows season selector controls and episode links.
- Current public episode route observed: `https://diziyou.net/bolum/from-4-sezon-10-bolum/`; rendered output exposes previous/next episode navigation and a player iframe.
- The iframe points to a `ksdpictures.site/dzembed.php?id=...` endpoint. Direct inspection returned HTTP 403 from the research environment, so the embed response, playback source, subtitle track behavior and required request context remain unverified.
- This confirms the URL route shapes from rendered pages, but does **not** establish reliable raw HTML selectors or a usable player integration. No Diziyou provider code was added on the basis of this incomplete evidence.
- Next verification required: obtain a sanitized, ordinary public response for catalogue/detail/episode pages and inspect only the iframe/embed behavior available without bypassing access controls; then add fixture-backed parser tests before provider implementation.


## APK-first implementation correction — 2026-10-12

The APK is not merely a list of website names: its DEX contains provider repository and resolver implementations. The next implementation pass must start from those classes and their request/parse flow, then validate the current remote configuration and playback behavior. Public-site browsing is secondary evidence, not a replacement for inspecting the app's own implementation.

Class-level evidence found in the supplied THY TV 5.0 DEX:
- Series providers: `FilmMakinesiRepository`, `FullHdFilmRepository`, `DizillaRepository`, `DizipalRepository`, `DiziyouRepository`, `Turkish123Repository`.
- Live TV: `CanlikolikRepository`, `LiveTvRepository`, `LiveTvStreamRepository`, `TabiiTvRepository`, `TabiiExtractor`.
- Sports: `CanlitribunHtmlParser`, `PapazRepository`, `PapazBaseUrlRepository`.
- Supporting domain/configuration logic: `RemoteDomainRepository`, `SeriesDomainRepository`, `CanlikolikConfig`, `CanlikolikFeedApplier`, and `PapazConfig`.
- Static strings include the official tabii live route `https://www.tabii.com/tr/watch/live/trt1` and a sample player URL `https://araplarsitesi.com/api/player?vid=wJXg8-2kr5s`; the latter is an example string, **not** accepted as a current provider endpoint.

This pass recovered class names and literal strings from DEX, but a full method-level port still requires decompilation/inspection of the relevant bytecode and identifying any runtime-fetched configuration. Do not infer that an old literal or example URL is the active base domain. The next code changes should port one missing provider at a time from its repository/parser/resolver flow and add tests against sanitized, evidence-backed cases. Existing repo providers remain untouched.


### DiziYou prior-art comparison — 2026-10-12
- Inspected a maintained public CloudStream DiziYou implementation in Kekik-cloudstream. It documents an older `diziyou3.com` host and a player convention based on `iframe#diziyouPlayer`, option IDs, HLS path patterns and VTT subtitles.
- The current public site observed for this project is `diziyou.net`; an episode page's iframe was observed pointing to `ksdpictures.site/dzembed.php?id=...`, but the embed returned HTTP 403 during inspection.
- These are not yet proven to be the same player protocol. The old HLS/subtitle URL construction is **not** copied into a new provider without current evidence. Detailed gate: [HATY_DIZIYOU_EVIDENCE.md](HATY_DIZIYOU_EVIDENCE.md).


### DiziYou module added — 2026-10-12
- Added independent Gradle module `HatyDiziYou` on the feature branch. It is auto-discovered by `settings.gradle.kts` and therefore joins the single repo feed when the build succeeds.
- The adapter uses the currently observed `diziyou.net` host and the known `/dizi/` and `/bolum/` route families; it does not copy the old provider's constructed HLS/subtitle paths.
- Status remains beta. Search and pagination are deliberately disabled pending confirmed route/API evidence; live playback and subtitles are **NOT TESTED/BLOCKED**.


### tabii module added — 2026-10-12
- Added independent `HatyTabii/` beta module based on the APK's `TabiiTvRepository`/`TabiiExtractor` evidence and observed official route `/tr/watch/live/trt1`.
- It discovers live-page links and only emits media URLs explicitly present in the official response; it does not synthesize manifests. If the service uses an authenticated or DRM-protected player not exposed in page HTML, playback remains blocked/unverified.
- Search, channel completeness, manifest access, and real CloudStream playback are not yet verified.


### Papaz beta module added — 2026-10-12
- Added independent `HatyPapaz/` module using publicly discoverable `papazsports.co` pages. The APK inventory confirms Papaz repository/config classes, but does **not** prove that this domain matches the APK's current runtime configuration.
- It parses event/channel-like links from page HTML and resolves only explicitly exposed iframe/video/manifest candidates. No generated stream URLs or access-control bypasses are used.
- Catalogue completeness, current domain match, stream access, playback, and subtitles remain **NOT TESTED**.


## Canonical completion status — refreshed 2026-10-12

This section supersedes earlier dated snapshots in this file where implementation checkbox wording conflicts with the current branch.

| APK family | Module status | Evidence/status boundary |
|---|---|---|
| FilmMakinesi | Existing; protected | Not duplicated or changed in this PR |
| Dizilla | Existing; protected | Not duplicated or changed in this PR |
| FullHDFilmizlesene | Existing; protected | Not duplicated or changed in this PR |
| DDizi | Existing; protected | Not changed in this PR |
| Turkish123 | HatyTurkish123 beta | HTML catalogue/detail/episode URLs are parsed; search, pagination, playback and subtitles remain unverified |
| DiziYou | HatyDiziYou beta | diziyou.net route shapes observed; player embed responded 403 in research environment; playback unverified |
| DiziPal | HatyDiziPal beta | dizipal1430.com redirected to dizipalorjinal14.com on 2026-10-12; live home catalog rendered. GET /api/search.php?q=..., /dizi/, /film/, /bolum/ and videoplay.vip behavior remain secondary code evidence, not independently verified raw responses or proof of APK resolver equivalence |
| tabii | HatyTabii beta live provider | Official live route observed; stream exposure and device playback not verified; no access-control bypass |
| Papaz | HatyPapaz beta | papazsports.co is a discovery lead only, not confirmed as APK active remote base; live playback unverified |
| Canlikolik | Not implemented | Public evidence indicates a rotating canlikolik*.my domain family, but not the APK's live remote configuration or channel/player responses |
| CanlıTribün | Not implemented | Public evidence indicates rotating canlitribun*.live and canlitribun*.top families, but no verified event/player response chain |

Latest CI before this commit passed at [workflow run 38257290766](https://github.com/berr49300/cloudstream-film-repo/actions/runs/38257290766). The current branch CI is being rerun after the DiziPal host and pagination correction. Build and unit tests do not verify playback or subtitles. main and builds remain untouched.


## Live/sports implementation update — 2026-10-12

- Added separate HatyCanlikolik and HatyCanliTribun beta modules. Both use bounded candidate-domain lists, same-family event/channel filtering, and only literal iframe/video/source/manifest URLs in returned HTML. The APK remote live-domain feed and device playback are not verified.
- Updated HatyPapaz to public mirror candidates papazsports1022.pro and papazsports1023.pro. This is discovery evidence, not proof of the APK's active remote configuration.
- If mirror requests are blocked or expose only a challenge/shell, the adapter returns no invented stream and marks runtime behavior unverified.

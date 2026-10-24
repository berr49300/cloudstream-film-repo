# WebteIzle Site Profile

- Base URL: https://webteizle.info/
- Date checked: 2026-10-24
- Overall status: INVESTIGATING
- Public site reachable: PARTIAL through rendered web pages; raw HTML/scripts/player responses were not accessible to the local shell
- Content type in this provider: Movies
- Provider branch: feature/webteizle-provider
- PR: https://github.com/berr49300/cloudstream-film-repo/pull/35

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
|---|---|---|---|
| Public catalogue and film cards exist | CONFIRMED | Homepage shows titles, year, rating/genre and pagination: https://webteizle.info/ | Raw DOM/card markup and image attributes still need direct response capture |
| Pagination URL for the main catalogue | CONFIRMED | Clicking page 2 from the home page resolves to https://webteizle.info/film-izle/2 | Verify HTTP status/redirect headers and page parity from the provider runtime |
| New-films pagination | CONFIRMED | https://webteizle.info/yeni-filmler and https://webteizle.info/yeni-filmler/2 both render catalogue pages | Verify provider page-count and item parity |
| Recommendation and award category routes | CONFIRMED | https://webteizle.info/tavsiye-filmler and https://webteizle.info/odullu-filmler render catalogue pages | Verify each page's raw DOM and page count |
| Detail page metadata | PARTIAL | https://webteizle.info/hakkinda/love-language renders title, year, country, genre, runtime and synopsis | Poster, score and exact selectors need a raw DOM fixture |
| Search URL/encoding | HYPOTHESIS | A public CloudStream reference uses GET /filtre?a= with ISO-8859-9 encoding | Live request/response was not available to this environment; test ASCII, Turkish diacritics, IMDb IDs and empty search |
| Playback alternatives AJAX endpoints | HYPOTHESIS | Public reference implementation uses POST /ajax/dataAlternatif3.asp and /ajax/dataEmbed.asp | Capture current request form, JSON/HTML response, status, content type and headers from live runtime |
| Player host families | HYPOTHESIS | Other provider code references Vidmoly, OK.ru, Filemoon, Pixeldrain, RubyVid and Dzen | Do not assume any host is active for every film; enumerate from current responses |
| Real source extraction | NOT TESTED | Provider now probes HTML/AJAX embed responses and gives each discovered candidate a resolver ID | Need real response payload, CloudStream source-list test and per-ID playback report |
| Subtitles and alternate audio | NOT TESTED | Scanner can discover HTML track elements and HLS audio/subtitle declarations | Validate actual URLs, formats, language, timing and rendering in CloudStream |

## Implementation notes

- The provider and resolver scanner are isolated under `WebteIzle/`; no existing provider or shared code was edited.
- `WebteIzlePlugin.kt` is the `@CloudstreamPlugin` entry point. This was missing in the first draft and caused the initial CI failure.
- `WebteIzleResolverBank.kt` contains a 37-ID diagnostic matrix. Methods that find the same URL intentionally keep separate IDs; this early stage does not deduplicate across resolver methods.
- A candidate appearing in CloudStream means only that a URL was found and routed to a media link or extractor. It does not prove HTTP validity or playback.
- Each scan logs resolver ID, name, status and reason without logging full candidate URLs or query strings. Candidate names are also exposed to CloudStream for manual comparison.
- Evidence-dependent methods are not falsely marked as executed: JavaScript runtime execution is explicitly not applicable to the static scanner; nested iframe fetch, redirect/header comparisons, browser network capture and user-supplied evidence still need separate testing.
- No CAPTCHA, authentication, DRM or other access-control bypass is included. No signed playback URL or token is stored in this repository.
- Public reference reviewed for endpoint/selector clues: https://github.com/burak-ayd/cloudstream-repo/blob/main/WebteIzle/src/main/kotlin/com/keyiflerolsun/WebteIzle.kt. It is reference evidence, not proof of current live-site behavior; this implementation is a separate implementation.
- The older reference noted in the previous investigation is https://github.com/nikyokki/nik-cloudstream/tree/master/WebteIzle.

## Resolver inventory and current capability

Important: “probe implemented” means the code can inspect an input page/manifest for that pattern. It does **not** mean the live WebteIzle page triggered it, that a valid stream was returned, or that video played. Every live row remains UNVERIFIED until current payloads are observed.

| ID | Resolver | Current status |
|---:|---|---|
| 01 | Html5SourceResolver | Probe implemented; live not tested |
| 02 | LazyDataAttributeResolver | Probe implemented; live not tested |
| 03 | SelectedPlayerElementResolver | Probe implemented; live not tested |
| 04 | JsonLdMediaResolver | Probe implemented; live not tested |
| 05 | EmbeddedJsonResolver | Probe implemented; live not tested |
| 06 | HydrationStateResolver | Marker detection and URL scan implemented; live not tested |
| 07 | PlayerConfigObjectResolver | Probe implemented; live not tested |
| 08 | StaticScriptUrlResolver | Probe implemented; live not tested |
| 09 | EscapedScriptUrlResolver | Common JS-escape normalization implemented; live not tested |
| 10 | JavaScriptVariableResolver | Probe implemented; live not tested |
| 11 | JavaScriptRuntimeResolver | NOT APPLICABLE in current static scan; no script execution |
| 12 | PackedScriptResolver | Signature detection only; unpacking not implemented/verified |
| 13 | KnownEncodingResolver | Observed `atob()` Base64 values tested by the scanner; live not tested |
| 14 | SiteSpecificObfuscationResolver | Marker detection only; no guessed decoder |
| 15 | IframeDiscoveryResolver | Probe implemented; live not tested |
| 16 | NestedIframeResolver | NOT TESTED; bounded child-page fetch still needed |
| 17 | ExtractorRegistryResolver | Routed via CloudStream `loadExtractor`; live output not tested |
| 18 | ObservedRestApiResolver | API-call marker diagnostic; no schema inferred |
| 19 | ObservedAjaxResolver | Provider POST path plus response audit; live payload not verified here |
| 20 | GraphQlResolver | GraphQL marker diagnostic; no query inferred |
| 21 | PlayerIdManifestResolver | NOT APPLICABLE until a real ID-to-manifest template is evidenced; no URLs synthesized |
| 22 | RedirectChainResolver | NOT TESTED; HTTP redirect metadata is not recorded by the static scanner |
| 23 | HlsMasterPlaylistResolver | M3U8 URL classification implemented; manifest validity not tested |
| 24 | HlsVariantPlaylistResolver | Parses variants when an actual master-manifest body is supplied; live not tested |
| 25 | DashManifestResolver | MPD URL classification implemented; manifest validity not tested |
| 26 | DirectMediaFileResolver | MP4/M4V/WebM candidate classification implemented; live not tested |
| 27 | ContainerFormatCandidateResolver | MKV/AVI/MOV candidate classification implemented; live not tested |
| 28 | SegmentPlaylistResolver | Segment signals recognized; TS/M4S segments deliberately not offered as complete videos |
| 29 | BrowserNetworkDiscoveryResolver | NOT TESTED; requires browser/network evidence |
| 30 | BlobMediaSourceDiagnostic | Blob marker diagnostic; underlying stream not traced yet |
| 31 | ServiceWorkerPlaybackDiagnostic | Service Worker marker diagnostic; not executed in CloudStream |
| 32 | SignedUrlResolver | Signed-query candidates kept separately; URL values are not logged; live not tested |
| 33 | HeaderContextResolver | NOT TESTED beyond the existing parent-page referer; alternate headers are not guessed |
| 34 | SessionDependentSourceDiagnostic | Challenge/session marker diagnostic; live status/header comparison not tested |
| 35 | AlternateAudioAndSubtitleResolver | HTML track and HLS group parsing implemented; subtitle playback not tested |
| 36 | GenericExtractorFallback | Separate candidate path through `loadExtractor`; live output not tested |
| 37 | ManualNetworkEvidenceResolver | NOT TESTED; no user-supplied browser/CloudStream network evidence yet |

## Automated and manual test state

- Resolver unit tests: added, pending current CI result.
- Kotlin/build/package validation: current latest run must be checked; previous run failed because the plugin entry-point class was missing.
- Raw live HTML/AJAX response fixtures: NOT TESTED.
- Catalog/search parity: NOT TESTED from provider runtime.
- Poster loading inside CloudStream: NOT TESTED.
- Per-candidate HTTP/manifest validation: NOT TESTED.
- CloudStream source-list confirmation: NOT TESTED.
- Real playback/audio selection: NOT TESTED.
- Subtitle rendering: NOT TESTED.
- Publication: NOT PUBLISHED; do not merge or publish until validation and approval.


## 2026-10-25 — False-positive source incident and regression fix

### Incident evidence

- **CONFIRMED from the user's CloudStream screenshot:** IDs 07 (PlayerConfigObjectResolver), 17 (ExtractorRegistryResolver) and 36 (GenericExtractorFallback) all surfaced `youtu.be` URLs as source candidates, including repeated rows at 720p/480p. The host/path match was being treated as a candidate even though it was a YouTube-hosted clip, not a confirmed WebteIzle film stream.
- **CONFIRMED in code:** IDs 17 and 36 were built from player URLs already found by other scan IDs; the same embed URL could also be processed from the detail page and multiple AJAX language paths. Resolver IDs therefore described alternate discovery/routing paths, not independent media origins.
- **CONFIRMED in code:** the older `looksLikeMediaOrPlayer` predicate accepted generic paths such as `/watch` and `/embed`; it did not exclude YouTube hosts.
- **PARTIAL external check (2026-10-25):** the public homepage rendered catalogue entries, but the detail-page research request did not return a readable page in the available research environment. No raw current AJAX/player payload was captured. This does not prove the site is globally offline or prove the Android runtime receives the same response.

### Code changes in `fix/webteizle-real-player-candidates`

- All resolver ingress paths reject `youtube.com`, `youtu.be`, `youtube-nocookie.com`, YouTube API hosts and `googlevideo.com` URLs. Such addresses are not emitted as playable source candidates.
- Recognize the user-named player families as valid player-shaped URLs even when their path has no obvious `/embed` or media-file suffix: VidMoly, Filemoon, PixelDrain, and OK.ru. The provider routes these embed pages through CloudStream's existing `loadExtractor`; it does not synthesize media URLs.
- Deduplicate an identical URL within the same resolver ID across overlapping detail/AJAX scans. Distinct resolver IDs remain visible because the test plan requires each strategy to be diagnosable. A repeated resolver ID is no longer repeated for the same URL.
- Extractor exceptions and no-link outcomes are logged with resolver ID and host family while omitting full URLs/query strings.
- Detect Cloudflare/CAPTCHA challenge HTML and report `BLOCKED`; no challenge-solving, authentication bypass, or DRM bypass is added.

### Host-family support scope (not live-verified)

| Family | Host variants recognized by this adapter | Resolution path |
|---|---|---|
| VidMoly | `vidmoly.to`, `vidmoly.me`, `vidmoly.net`, `vidmoly.sx` | CloudStream extractor registry |
| Filemoon | `filemoon.to`, `filemoon.sx`, `filemoon.in`, `filemoon.nl` | CloudStream extractor registry |
| PixelDrain | `pixeldrain.com`, `pixeldra.in` | CloudStream extractor registry |
| OK.ru | `ok.ru`, `odnoklassniki.ru` | CloudStream extractor registry |

Recognizing a host means the URL is routed to its extractor; it is not evidence that the host is currently reachable, the WebteIzle film points to it, its API is compatible, or the stream plays. Any further domain aliases should be added only when an actual current embed URL or maintained extractor supports them.

### Regression tests added

- YouTube watch, youtu.be short links, YouTube-nocookie embeds, and googlevideo CDN URLs never remain in any resolver's candidate list.
- VidMoly, Filemoon, PixelDrain and OK.ru embed URLs are recognized without requiring a media extension.
- A Cloudflare-style "Just a moment / challenge-platform" page is classified as an access challenge.

### Remaining gates

- **NOT TESTED:** current live `dataAlternatif3.asp` and `dataEmbed.asp` request/response shape from a normal CloudStream runtime.
- **NOT TESTED:** whether the four named hosts are returned by the current film's actual alternative-player list.
- **NOT TESTED:** HTTP response/manifest validity for emitted media, real playback, audio track and subtitle rendering.
- If Cloudflare denies the normal request, report `BLOCKED` and use sanitized, user-authorized network evidence for diagnosis; do not try to defeat the challenge.
- Do not label any source `MANUAL_PLAY_SUCCESS` until a real film (not a trailer) is confirmed playing on the user's device.

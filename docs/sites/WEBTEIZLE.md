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

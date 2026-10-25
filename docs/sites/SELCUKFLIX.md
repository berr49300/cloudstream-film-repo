# SelcukFlix provider — discovery ledger and resolver test bank

- Requested site: https://selcukflix.app/
- Provider module: SelcukFlix
- Module version: 1 (experimental / status 3)
- Scope: independent provider module; existing providers are not modified.
- Investigation date: 2026-10-25
- Overall status: INVESTIGATING
- Actual CloudStream playback: NOT TESTED

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
|---|---|---|---|
| The requested .app homepage can be read by this investigation environment | BLOCKED | Opening https://selcukflix.app returned HTTP 403 Forbidden in the web retrieval layer. This does not prove the site is down or that the Android app will also be blocked. | Does the normal CloudStream HTTP client receive the page, a challenge, or a different response? |
| Current .app catalog/API routes | HYPOTHESIS | A public SelcukFlix provider in https://github.com/nikyokki/nik-cloudstream uses /api/bg/findMovies, /api/bg/findSeries and a POST to /api/bg/searchcontent. That code currently points to selcukflix.net, not the requested .app domain. | Verify the route names and response fields against a real .app response before treating as confirmed. |
| Detail and episode data | HYPOTHESIS | The public reference implementation reads script#__NEXT_DATA__, then props.pageProps.secureData, decodes it as Base64 JSON, and reads content/season/episode fields. | Check the .app DOM and payload schema; field casing may have changed. |
| Movie source records | HYPOTHESIS | The reference implementation reads RelatedResults.getMoviePartsById and getMoviePartSourcesById_*, including source_content and quality_name. | Verify if current .app uses these keys and whether all source variants are included. |
| Series source records | HYPOTHESIS | The reference implementation reads RelatedResults.getEpisodeSources.result. | Verify current series episode source payload. |
| YouTube is accepted as a source | CONFIRMED (policy in this provider) | The resolver bank explicitly rejects YouTube, youtu.be, youtube-nocookie and googlevideo URLs. | No trailer URLs should be exposed as playback candidates. |
| Cloudflare/CAPTCHA handling | CONFIRMED (implementation behavior) | Challenge signatures are detected and the provider fails closed. No CAPTCHA, session, DRM, or access-control bypass is attempted. | Real .app challenge behavior is not tested. |
| Video playback / audio / subtitles in CloudStream | NOT TESTED | No live .app episode or device playback was available to this investigation. | Manual device test required after a build artifact exists. |

## Request-flow hypothesis

The implementation follows the closest public protocol evidence, without presenting it as current-site confirmation:

1. Category/search request -> POST /api/bg/findMovies, /api/bg/findSeries, or /api/bg/searchcontent.
2. A JSON response may contain a Base64 response property.
3. Detail/episode page may expose __NEXT_DATA__.props.pageProps.secureData.
4. Decoded payload may contain contentItem, relatedResults, seasons, episode slugs, and player source_content entries.
5. Source strings are passed through independent numbered resolver methods. Iframe URLs may be inspected to a maximum of six parents and one nested level.
6. Player links and any direct media candidates are emitted with their resolver ID. Source ID is a discovery method, not proof that playback works.

Steps 1–5 are HYPOTHESIS for the requested .app site until a normal response is captured.

## Resolver test bank

Status means applicability/evidence on the requested .app site, not whether the Kotlin branch exists.

| ID | Resolver | Current handling | Status |
|---:|---|---|---|
| 01 | Html5SourceResolver | Reads video/source src | HYPOTHESIS |
| 02 | LazyDataAttributeResolver | Reads player/video lazy attributes | HYPOTHESIS |
| 03 | SelectedPlayerElementResolver | Reads known generic player containers | HYPOTHESIS |
| 04 | JsonLdMediaResolver | Reads JSON-LD media fields when present | NOT TESTED |
| 05 | EmbeddedJsonResolver | Parses relevant JSON values/strings | HYPOTHESIS |
| 06 | HydrationStateResolver | Inspects Next.js state and decoded embedded payload | HYPOTHESIS |
| 07 | PlayerConfigObjectResolver | Reads file/src/url/sources-like fields | HYPOTHESIS |
| 08 | StaticScriptUrlResolver | Scans script text for literal HTTP URLs | HYPOTHESIS |
| 09 | EscapedScriptUrlResolver | Normalizes escaped URLs before scanning | HYPOTHESIS |
| 10 | JavaScriptVariableResolver | Extracts explicit URL assignments without executing JS | HYPOTHESIS |
| 11 | JavaScriptRuntimeResolver | No JS runtime is assumed or added without evidence/dependency review | NOT TESTED |
| 12 | PackedScriptResolver | Detects packed-script signature and scans visible literals only; no eval | NOT TESTED |
| 13 | KnownEncodingResolver | Decodes bounded Base64 tokens only when decoded text contains useful media/JSON markers | HYPOTHESIS |
| 14 | SiteSpecificObfuscationResolver | Tries the previously observed secureData Base64 envelope | HYPOTHESIS |
| 15 | IframeDiscoveryResolver | Reads iframe src/data-src/data-lazy-src | HYPOTHESIS |
| 16 | NestedIframeResolver | Fetches up to six distinct iframe pages and inspects one nested level; blocked responses fail closed | NOT TESTED |
| 17 | ExtractorRegistryResolver | Sends discovered player URLs through CloudStream loadExtractor | HYPOTHESIS |
| 18 | ObservedRestApiResolver | Parses a source URL found in the API field being inspected | HYPOTHESIS |
| 19 | ObservedAjaxResolver | Emits candidates only if an AJAX/XHR/fetch marker is actually present | NOT TESTED |
| 20 | GraphQlResolver | Conditional inspection only when GraphQL markers appear | NOT TESTED |
| 21 | PlayerIdManifestResolver | Reads explicit manifest/playlist values; never guesses a URL from an ID alone | NOT TESTED |
| 22 | RedirectChainResolver | Redirect behavior is left to the normal HTTP client/extractor; chain is not yet separately instrumented | NOT TESTED |
| 23 | HlsMasterPlaylistResolver | Recognizes explicit .m3u8 URLs and attempts to inspect accessible manifests | HYPOTHESIS |
| 24 | HlsVariantPlaylistResolver | Parses EXT-X-STREAM-INF variants from an accessible master playlist | NOT TESTED |
| 25 | DashManifestResolver | Recognizes explicit .mpd URLs | HYPOTHESIS |
| 26 | DirectMediaFileResolver | Recognizes explicit MP4/M4V/WebM URLs | HYPOTHESIS |
| 27 | ContainerFormatCandidateResolver | Recognizes explicit MKV/AVI/MOV URLs when observed | NOT TESTED |
| 28 | SegmentPlaylistResolver | Does not expose .ts/.m4s segments as full videos; valid playlist context is required | NOT TESTED |
| 29 | BrowserNetworkDiscoveryResolver | CloudStream cannot read browser devtools network state; waiting for sanitized user-provided evidence if needed | NOT TESTED |
| 30 | BlobMediaSourceDiagnostic | Never emits blob: as a portable video URL; scans for nearby HTTP dependencies | NOT TESTED |
| 31 | ServiceWorkerPlaybackDiagnostic | Does not assume a browser Service Worker exists in CloudStream | NOT TESTED |
| 32 | SignedUrlResolver | Keeps an observed signed URL intact for the request; never logs its query/token | NOT TESTED |
| 33 | HeaderContextResolver | Adds an alternate site-origin referer attempt for discovered iframes | HYPOTHESIS |
| 34 | SessionDependentSourceDiagnostic | Marks Cloudflare/CAPTCHA challenge as BLOCKED; does not bypass it | CONFIRMED (fail-closed behavior) |
| 35 | AlternateAudioAndSubtitleResolver | Reads track elements and HLS subtitle groups; actual rendering is untested | HYPOTHESIS |
| 36 | GenericExtractorFallback | Generic extractor is attempted separately; unresolved player page is visibly labelled as unresolved | HYPOTHESIS |
| 37 | ManualNetworkEvidenceResolver | Reserved for a real URL/response observed by the user; no user network evidence exists yet | NOT TESTED |

## Manual CloudStream test report

| ID / resolver | Result | Symptom |
|---|---|---|
| All candidates | NOT TESTED | No build has been installed and no real device playback was performed during this investigation. |

Record 01 PLAY, 02 FAIL, etc. for the exact numbered label. If multiple IDs resolve to the same player URL, the duplicate entries are intentional for diagnosis; they can be consolidated only after the test results show which path is useful.

## Third-party prior-art review

- Reference: https://github.com/nikyokki/nik-cloudstream/tree/master/SelcukFlix
- The reference repository declares GNU GPL-3.0.
- This provider was written independently for this repository. The reference was used to identify possible route/field names only; its Kotlin source is not copied into the new module and is not a runtime dependency.
- The endpoint and payload shapes above are attributed as HYPOTHESIS because the reference uses a different domain (selcukflix.net). Current .app behavior is not confirmed.

## Test/build/release status

- Kotlin unit tests: added; CI result pending.
- Full Gradle build and repo validator: NOT TESTED locally; CI must run on the pull request.
- Generated .cs3 package and plugins.json: NOT YET GENERATED for this branch.
- Manual playback: NOT TESTED.
- Publishing: not performed. Publication is a separate action and requires explicit approval.

## Safety boundary

Only normal public HTTP requests are attempted. Cloudflare/CAPTCHA, login, session restrictions, DRM, or other access controls are not bypassed. Active signed URLs, cookies and request tokens must not be committed to fixtures or logs.

# SelcukFlix provider — discovery ledger and resolver test bank

- Requested site: https://selcukflix.app/
- Provider module: \`SelcukFlix\`
- Module version: 16 (experimental / status 3; multi-source discovery and generic extractor fallback in branch fix/selcukflix-multisource-fallbacks)
- Investigation date: 2026-10-26
- Scope: separate module only; no existing provider files were changed.
- Live site access from this environment: BLOCKED (web retrieval returned HTTP 403).
- CI for v14 branch: NOT TESTED yet; unit tests and package build must run before merge.
- Actual playback in CloudStream: NOT TESTED.

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
|---|---|---|---|
| Requested .app homepage is readable from this investigation environment | BLOCKED | The web retrieval layer returned HTTP 403 for https://selcukflix.app/. That does not prove the site is down or that CloudStream on the user's device is blocked. | Determine what the normal CloudStream HTTP client gets: HTML, a challenge, or another response. |
| Current domain catalog routes | HYPOTHESIS | Public provider for the current domain uses \`/dizi-izle\`, \`/film-izle\`, \`/trend\`, and \`/kesfet\`. Reference: https://github.com/SafakStream/SafakStream-Extensions/tree/main/SelcukFlix | Verify the current .app response in a normal client. |
| Search API route | HYPOTHESIS | Public current-domain provider uses POST \`/api/bg/searchContent?searchterm=...\` and a JSON \`response\` payload. | Live endpoint response not available in this investigation. |
| Encrypted page/API payload | HYPOTHESIS | Public current-domain implementation decodes \`#__NEXT_DATA__\`, \`props.pageProps.secureData\`, and API \`response\` using an AES-CBC page-data envelope. This repository independently implements the payload decode routine; it does not copy the reference source. | Confirm current .app payload is still the same. |
| Detail/episode route pattern | HYPOTHESIS | Current-domain reference parses visible metadata, season links \`/sezon-N\`, episode links \`/sezon-N/bolum-N\`, and Next.js state. | Live .app DOM and payload schema not seen. |
| Pichive/DPlayer82 player flow | HYPOTHESIS | Public current-domain reference describes \`iframe.php?v=...\` → \`openPlayer(token)\` → \`source2.php?v=token\` JSON with \`playlist[].sources[].file\`; one pattern maps an observed \`m.php\` path to \`master.m3u8\`. New resolver IDs 38/39 preserve the raw and transformed candidates separately. | Current live player response not available. |
| YouTube results | CONFIRMED (provider policy) | Resolver code rejects \`youtube.com\`, \`youtu.be\`, \`youtube-nocookie.com\`, and \`googlevideo.com\`. | Real live catalog is still untested. |
| Cloudflare/CAPTCHA | CONFIRMED (fail-closed implementation) | Recognized challenge bodies and likely access-denied exceptions are reported BLOCKED. The implementation does not retry with challenge-workaround headers or try to bypass CAPTCHA, session limits, or DRM. | Normal device behavior remains unknown. |
| Candidate URLs / real playback | NOT TESTED | Candidate extraction is tested against fixtures only. No live episode was playable in this environment, and no device playback was performed. | User must test the built artifact in CloudStream. |

## Request flow currently implemented

1. Catalog page GET: \`/dizi-izle\`, \`/film-izle\`, \`/trend\`, \`/kesfet\`; parses visible cards, falling back to decoded \`initialSeries\` when available.
2. Search POST: \`/api/bg/searchContent?searchterm=...\`; decodes the \`response\` envelope if present.
3. Detail: parses Open Graph/title metadata first and decrypted page data when available; tries numeric season/episode route links and sorts by season then episode.
4. Playback: reads player/source URL candidates from decoded source values, scripts, HTML5 elements, and iframe links.
5. Pichive IDs 38/39: normal iframe-context request → \`openPlayer(token)\` → \`source2.php\` JSON source entries → raw source and independent \`m.php\`/master-playlist candidate.
6. HLS manifests are inspected only when an explicit candidate manifest is accessible; variants and subtitle tracks are reported separately.
7. If any response is a challenge, that path is BLOCKED. No challenge bypass is attempted.

Routes and field names above are HYPOTHESIS for the current .app domain until a real response is observed.

## Historical candidate test bank (39 diagnostic IDs; not 39 working sources)

These IDs were hypotheses for recognizing candidate data, not 39 confirmed playback paths. They must not be advertised as 39 working sources. The focused player path is iframe.php → openPlayer(token) → source2.php → playlist[].sources[].file. The master.m3u8 transformation remains unverified against a live site response from this environment.

| ID | Resolver | Current handling | Site evidence status |
|---:|---|---|---|
| 01 | Html5SourceResolver | Reads \`video\`/\`source\` src | HYPOTHESIS |
| 02 | LazyDataAttributeResolver | Reads player/video lazy attributes | HYPOTHESIS |
| 03 | SelectedPlayerElementResolver | Reads generic selected-player containers | HYPOTHESIS |
| 04 | JsonLdMediaResolver | Reads JSON-LD media when present | NOT TESTED |
| 05 | EmbeddedJsonResolver | Parses embedded JSON values | HYPOTHESIS |
| 06 | HydrationStateResolver | Reads Next.js state and embedded payload | HYPOTHESIS |
| 07 | PlayerConfigObjectResolver | Reads fields such as \`file\`, \`src\`, \`url\`, \`sources\` | HYPOTHESIS |
| 08 | StaticScriptUrlResolver | Scans script text for literal URLs | HYPOTHESIS |
| 09 | EscapedScriptUrlResolver | Normalizes escaped URLs before scanning | HYPOTHESIS |
| 10 | JavaScriptVariableResolver | Extracts explicit URL assignments without executing scripts | HYPOTHESIS |
| 11 | JavaScriptRuntimeResolver | No runtime added; requires evidence and dependency review first | NOT TESTED |
| 12 | PackedScriptResolver | Detects known packed-script signature; does not execute packed code | NOT TESTED |
| 13 | KnownEncodingResolver | Decodes bounded Base64 values only when they yield relevant content | HYPOTHESIS |
| 14 | SiteSpecificObfuscationResolver | Tries the observed page-data envelope | HYPOTHESIS |
| 15 | IframeDiscoveryResolver | Reads iframe src/data-src/lazy src | HYPOTHESIS |
| 16 | NestedIframeResolver | Limited depth; skips challenged responses | NOT TESTED |
| 17 | ExtractorRegistryResolver | Sends discovered player URLs through CloudStream extractor registry | HYPOTHESIS |
| 18 | ObservedRestApiResolver | Parses explicit source URLs from API payload | HYPOTHESIS |
| 19 | ObservedAjaxResolver | Conditional on observed AJAX/XHR/fetch marker | NOT TESTED |
| 20 | GraphQlResolver | Conditional on GraphQL markers | NOT TESTED |
| 21 | PlayerIdManifestResolver | Reads explicitly observed manifest fields; does not guess from ID alone | NOT TESTED |
| 22 | RedirectChainResolver | Relies on normal client/extractor redirect handling; no independent redirect report yet | NOT TESTED |
| 23 | HlsMasterPlaylistResolver | Recognizes explicit .m3u8 candidates and inspects accessible manifest | HYPOTHESIS |
| 24 | HlsVariantPlaylistResolver | Parses EXT-X-STREAM-INF variants | NOT TESTED |
| 25 | DashManifestResolver | Recognizes explicit .mpd candidates | HYPOTHESIS |
| 26 | DirectMediaFileResolver | Recognizes MP4/M4V/WebM | HYPOTHESIS |
| 27 | ContainerFormatCandidateResolver | Recognizes observed MKV/AVI/MOV | NOT TESTED |
| 28 | SegmentPlaylistResolver | Does not present raw TS/M4S segment as a whole video | NOT TESTED |
| 29 | BrowserNetworkDiscoveryResolver | Waits for sanitized, user-provided network evidence; CloudStream can't inspect browser devtools | NOT TESTED |
| 30 | BlobMediaSourceDiagnostic | Never emits blob: as a portable source; scans nearby HTTP references | NOT TESTED |
| 31 | ServiceWorkerPlaybackDiagnostic | Does not assume browser Service Worker support in CloudStream | NOT TESTED |
| 32 | SignedUrlResolver | Preserves an observed signed URL for use; does not log query/token | NOT TESTED |
| 33 | HeaderContextResolver | Uses ordinary observed embedding referer context; no challenge workaround | HYPOTHESIS |
| 34 | SessionDependentSourceDiagnostic | Reports Cloudflare/CAPTCHA/access challenge as BLOCKED | CONFIRMED (fail-closed behavior) |
| 35 | AlternateAudioAndSubtitleResolver | Reads track tags and HLS subtitle groups; label and language are kept separate | HYPOTHESIS |
| 36 | GenericExtractorFallback | Runs the generic CloudStream extractor for player pages; unresolved pages are clearly labelled | HYPOTHESIS |
| 37 | ManualNetworkEvidenceResolver | Reserved for the user's observed source/response; no supplied evidence yet | NOT TESTED |
| 38 | PichiveSourceApiResolver | Parses \`openPlayer(token)\` and a normal \`source2.php\` JSON response; keeps raw file URL | HYPOTHESIS |
| 39 | PichiveMasterPlaylistResolver | Separately tests observed \`m.php\` → \`master.m3u8\` transformation | HYPOTHESIS |

The IDs indicate discovery methods, not 39 guaranteed working sources. Similar URLs may deliberately appear under multiple IDs so that the user can test which method discovered them.

## Regression test and CI results

- Run: https://github.com/berr49300/cloudstream-film-repo/actions/runs/40183788567
- CI unit tests: PASS after correcting readable subtitle label precedence (\`NAME\`/track label before ISO language code).
- .cs3 build: PASS.
- Repository validator: PASS.
- Generated test artifact: \`cloudstream-plugin-build\`, artifact ID \`12595570904\`, expires 2026-11-01 10:13:58 UTC. Artifact can be downloaded from the Actions run; it is not published to the builds branch.
- Real live site data and device playback/audio/subtitle rendering: NOT TESTED.
- v3 was merged and published from PR #42 on 2026-10-25. The v4 source-probe/poster branch is separate; do not treat it as published until Actions and builds/plugins.json confirm it.

## v4 source-probe and poster hardening (2026-10-25)

- Source loading scans the decrypted payload, Next.js state, player-related script blocks and raw HTML independently.
- Candidate IDs remain visible. The raw Pichive source and the hypothesis `m.php` → `master.m3u8` are separate candidates; iframe and site-origin referer contexts remain separate.
- If an observed player URL fails through the installed extractor, it is still exposed as a clearly marked `PROBE ONLY (player URL, media NOT verified)` entry under the original discovery ID. This makes it manually testable instead of silently returning no links; it is not claimed to be playable media.
- Poster lookup prioritizes poster/face fields, recursively checks nested fields, considers lazy-load/srcset attributes and OG/Twitter image metadata, rejects common placeholders, and attempts a bounded detail-page fallback for up to 8 missing posters in the unfiltered first-page latest-series list.
- Regression tests cover nested poster aliases/placeholders and separate raw/derived media candidates.
- CI: PASS for code commit `9837f4d08c9a51b4f32de2339d39d28350a13c4a` — https://github.com/berr49300/cloudstream-film-repo/actions/runs/40195467117 (unit tests, `.cs3` build and repository validator passed; publish step skipped because this is a PR). An earlier run had a transient Gradle snapshot dependency resolution failure; the successful run completed afterward. Real live `.app` response and CloudStream playback remain BLOCKED / NOT TESTED from this environment.
\n## Third-party prior-art review

- Older source: https://github.com/nikyokki/nik-cloudstream/tree/master/SelcukFlix — GPL-3.0; points at the former \`selcukflix.net\` domain.
- Current-domain source: https://github.com/SafakStream/SafakStream-Extensions/tree/main/SelcukFlix — GPL-3.0; references \`selcukflix.app\` and the Pichive-style player flow.
- The code in this repository was written independently; no provider source file was copied and no third-party project is a runtime dependency. Prior-art protocol details are used as hypotheses, not as proof of the current site's behavior.

## v14 playback and latency corrections (2026-10-26)

- Root cause found in the player URL builder: constructing a URI with an already-escaped raw query double-encoded percent escapes (for example %2F became %252F). This could invalidate encoded media query parameters and also affected source2.php token requests.
- The builder now composes the URL while preserving the raw query for the HLS transformation; the source API token is encoded once.
- The observed PHP wrapper is transformed to the actual master.m3u8 URL and is not also emitted as a second pretend video source.
- Series detail uses structured episode data first; it requests season pages only if structured episodes are absent.
- Catalog no longer performs extra detail-page HTTP requests just to recover posters. Synthetic episode-filter rows were removed; catalog now exposes the four real site routes. Pagination uses sayfa, matching the current-domain reference implementation.
- Added regression tests for preserving %2F / %3D query escapes and encoding a source2.php token exactly once.
- These are static code and regression-test corrections, not proof of live playback. Live selcukflix.app response and playback on an actual CloudStream device remain NOT TESTED / BLOCKED until verified.

## v15 rotating iframe-host matching (2026-10-26)

- Comparison with a working public SelcukFlix/Pichive implementation showed that the player resolver is invoked for the page-discovered `iframe.php?v=...` URL without relying on a fixed list of hostnames.
- v14 only dispatched to the dedicated resolver when the hostname contained `pichive`, `playru`, `contentx`, or `dplayer82`. A rotated host would fall through to the generic extractor, which does not necessarily implement the `source2.php` token flow.
- v15 matches valid HTTP(S) URLs with an `/iframe.php` path and a non-empty `v` query parameter. The provider only passes URLs discovered from SelcukFlix page content.
- Regression tests cover a previously unknown host, a wrong path, a missing token, and a non-HTTP scheme.
- This is a dispatcher correction backed by fixture tests; it does not establish that the live host currently returns media. Live-site access and real-device playback remain NOT TESTED from this environment.

## Safety boundary

Only normal public HTTP requests are attempted. Cloudflare/CAPTCHA, login/session restrictions, DRM, and other access controls are not bypassed. Active signed URLs, cookies, personal information, and request tokens must not be committed to fixtures or logs.


## v16 source-discovery and fallback repair (2026-10-26)

- Symptom reported by user: listings/details open, but no movie or episode starts in this repo; the user's BronzeCloud SelcukFlix extension had playable sources.
- Root cause found in code: when a URL matched the dedicated `iframe.php?v=...` parser, a failed dedicated parse prevented the general CloudStream extractor registry from running. This removed the intended fallback exactly for the player shape that most needed it.
- Change: scan all distinct `source_content` fields from hydrated detail/episode payloads (including each episode source and movie-part source entry); prioritize the derived `master.m3u8` hypothesis while retaining the raw `m.php` candidate; avoid shadowing CloudStream's built-in player extractors; and try the standard registry on the original and known DPlayer → Hotlinger host aliases.
- Regression test: sanitized nested payload with two episode sources and one movie-part source; checks that all distinct source blocks are retained.
- Live site availability from this environment: BLOCKED / NOT TESTED. Real video playback in CloudStream: NOT TESTED. The `m.php` → `master.m3u8` mapping remains unverified; preserving both candidates prevents that hypothesis from eliminating the raw fallback. The change adds fallback routes but cannot guarantee playback if the site or its player denies access.


## v16.1 — Extractor registry collision and DPlayer alias fallback

- Review of CloudStream's `loadExtractor` behavior confirmed that it selects the first registered extractor matching a URL host and returns after that extractor call. Registering the provider-only resolver under `four.pichive.online` could therefore shadow the built-in FourPichive extractor.
- The custom resolver is now invoked directly by the SelcukFlix provider instead of being globally registered; normal CloudStream extractors can then match their own host families.
- Added evidence-backed fallback aliases for `sn.dplayer74.site`, `sn.dplayer82.site`, and `sn.dplayer.site` to `sn.hotlinger.com`, while keeping the original URL first and preserving the raw query.
- The normal derived `master.m3u8` candidate is prioritized; the raw PHP source remains as fallback.
- Tests cover the alias mapping and query preservation. Live device playback remains NOT TESTED.

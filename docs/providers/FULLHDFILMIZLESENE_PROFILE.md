# Site Profile — FullHDFilmizlesene

- Base URL: https://www.fullhdfilmizlesene.now/
- Scope: CloudStream film provider
- Status: BLOCKED for live verification; build success is not runtime verification
- Reference project: https://github.com/Batuhan4/fullhdfilmizlesene-stremio (protocol research only; not a CloudStream implementation template)

## Evidence ledger

| Claim | Status | Evidence / limitation |
|---|---|---|
| Site HTML and selectors | BLOCKED | Direct inspection from the current research environment returned HTTP 403. Search-indexed snippets are not sufficient to validate live DOM selectors. |
| Search endpoint | HYPOTHESIS | Public reference describes `/autocomplete/q.php?q=<title>`; response shape must be checked from a real response before considering it verified. |
| Embedded source records | HYPOTHESIS | Public reference describes an `scx` object with ROT13 + Base64 encoded player URLs. Must be confirmed on a current page. |
| Player families and media URLs | BLOCKED | No current source page or CloudStream playback session was verified. |
| Subtitle declarations and rendering | NOT TESTED | No CloudStream device evidence yet. |

## CloudStream-specific implementation requirements

- Treat Stremio as prior-art for site protocol discovery only. Stremio stream objects, server-side proxying, headers and playback behavior are not interchangeable with CloudStream's `MainAPI` / `ExtractorApi` contract.
- Verify the provider annotation, plugin registration, module metadata, generated `.cs3` package and repository index.
- Parse only observed response shapes; add sanitized fixture-based tests for search, catalog, detail and source decoding.
- Test source extraction through CloudStream's actual extractor registry. A decoded iframe URL is not itself proof that CloudStream can resolve it.
- Do not mark live catalog, video, subtitles or install behavior PASS without in-app evidence.

## Current implementation status

- GitHub Actions build/package: PASS on the recorded workflow run.
- CloudStream installation on a device: NOT VERIFIED; the user reported an initial download error followed by an installed state.
- Home catalog: FAIL per user report (only “None” and “Random” visible).
- Search: NOT TESTED on device.
- Video playback and subtitles: NOT TESTED on device.

## Next investigation

1. Capture the exact CloudStream install error/log, because screenshots do not identify its cause.
2. Obtain a real current site response or sanitized page source to verify the homepage and search selectors.
3. Add fixture tests against that observed response before changing selectors.
4. Verify at least one source host with CloudStream's own extractor behavior; create a dedicated `ExtractorApi` only if an observed player family is not supported.
5. Rebuild, install that exact artifact, and record each device test separately.

## Repository implementation note

- This provider is a standalone CloudStream module, separate from Dizilla; it must not reuse Dizilla classes or helpers.
- Intended scope: film search, homepage cards, film details, and forwarding observed iframe/SCX sources to CloudStream's extractor system.
- The root Gradle configuration includes the module automatically.
- Search and SCX behavior remain hypotheses until checked against current live responses. Player hosts, subtitles, installation, and real-device playback are not verified unless separately recorded above.

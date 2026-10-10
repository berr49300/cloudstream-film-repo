# FilmMakinesi site profile

- Base URL: https://filmmakinesi.to/
- Provider module: FilmMakinesi
- Content scope: films and series
- Investigation status: BUILD_AND_INDEX_VERIFIED; DEVICE_TEST_PENDING
- Live site fetch from this automation environment: BLOCKED (the page-fetch tool returned HTTP 451; this does not establish that the user's device or CloudStream is blocked)
- Real-device CloudStream status: NOT TESTED

## Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| Site offers film and series browsing | CONFIRMED from indexed public pages | Public search-indexed homepage and /kesfet/ page show film and series cards. |
| Detail cards use a.item / data-title metadata | HYPOTHESIS | Selector behavior remains unverified because live HTML could not be fetched here. |
| Search uses /arama/?s=... | HYPOTHESIS | This route is not verified against a live response in this environment. |
| Series pages are under /dizi/ and have episode links containing bolum | HYPOTHESIS | The module includes selector fallbacks, but the live markup has not been verified here. |
| Player sources include closeload.filmmakinesi.to and rapid.filmmakinesi.to | HYPOTHESIS | These hostnames are inferred from player naming; current live responses have not been verified here. |
| Subtitles are provided as VTT and have language labels | HYPOTHESIS | Current live subtitle payload is not available for inspection here. |
| .m3u8, .mpd, .mp4, .mkv, WebM and related direct links can be returned by this implementation | CONFIRMED at parser/build level only | Extension-aware link classification and static tests; not proof that the website currently serves every format. |

## Request flow implemented

1. Home/categories and search request the website's public HTML with a browser user-agent and referer.
2. Card parsing prefers semantic card anchors, then restricts fallback results to same-site content routes.
3. load() parses film metadata or creates a series response from season/episode anchors.
4. loadLinks() inspects embedded frames, source buttons, media tags and inline player URLs.
5. Direct media URLs are emitted by media type. Other embeds are passed to CloudStream's registered extractors. The custom CloseLoad resolver currently extracts only URLs exposed directly in player HTML; obfuscated payload decoding is intentionally not included.
6. Subtitle discovery runs on both the content page and the CloseLoad player page, normalizes language labels, deduplicates identical URLs and orders Turkish, English, then Turkish Forced.

## Release gate

- [x] Provider module, registration and metadata added without modifying existing provider source files.
- [x] Subtitle parser regression tests cover Turkish / English / Turkish Forced labels, distinct tracks, URL de-duplication and escaped labels.
- [x] Earlier GitHub Actions build and unit tests passed for version 9: https://github.com/berr49300/cloudstream-film-repo/actions/runs/38087568366
- [x] The published plugin index contains FilmMakinesi version 9: https://github.com/berr49300/cloudstream-film-repo/blob/builds/plugins.json
- [ ] User installs this exact build and verifies search, detail, playback/source switching and all subtitle tracks on a real device.

The successful build proves compilation and the listed static parser tests only. Automated live-site retrieval remains blocked in this environment; actual current site responses, every player family / format and device playback still need a CloudStream runtime test.

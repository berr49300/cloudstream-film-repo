# FilmMakinesi site profile

- Base URL: https://filmmakinesi.to/
- Provider module: FilmMakinesi
- Content scope: films and series
- Investigation status: INVESTIGATING
- Live site fetch from this automation environment: BLOCKED (the page-fetch tool returned HTTP 451; this does not establish that the user's device or CloudStream is blocked)
- Real-device CloudStream status: NOT TESTED

## Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| Site offers film and series browsing | CONFIRMED from indexed public pages | Public search-indexed homepage and /kesfet/ page show film and series cards. |
| Detail cards use a.item / data-title metadata | HYPOTHESIS | Public reference provider in Emre-Kahveci/CloudStreamHub uses these selectors; live HTML could not be fetched here. |
| Search uses /arama/?s=... | HYPOTHESIS | Public reference provider uses this route; not verified against a live response in this environment. |
| Series pages are under /dizi/ and have episode links containing bolum | HYPOTHESIS | Public reference provider maps /dizi/ to series and looks for episode links by bolum; this module includes selector fallbacks. |
| Player sources include closeload.filmmakinesi.to and rapid.filmmakinesi.to | HYPOTHESIS | Public reference provider documents these player variants. |
| Subtitles are provided as VTT and have language labels | HYPOTHESIS | Public reference provider parses VTT file/label pairs; current live subtitle payload is not available for inspection here. |
| .m3u8, .mpd, .mp4, .mkv, WebM and related direct links can be returned by this implementation | CONFIRMED at parser/build level only | Extension-aware link classification and static tests; not proof that the website currently serves every format. |

## Request flow implemented

1. Home/categories and search request the website's public HTML with a browser user-agent and referer.
2. Card parsing prefers semantic card anchors, then restricts fallback results to same-site content routes.
3. load() parses film metadata or creates a series response from season/episode anchors.
4. loadLinks() inspects embedded frames, source buttons, media tags and inline player URLs.
5. Direct media URLs are emitted by media type. Other embeds are passed to CloudStream's registered extractors; FilmMakinesi's CloseLoad/Rapid variants are inspected with the dedicated resolver first.
6. Subtitle discovery runs on both the content page and the CloseLoad player page, normalizes language labels, deduplicates identical URLs and orders Turkish, English, then Turkish Forced.

## Release gate

- [x] Provider module, registration and metadata added without modifying existing provider source files.
- [x] Parser regression tests added for film/series card separation, episode numbering, subtitle labels/order, direct HLS/MP4 discovery and a CloseLoad decoder fixture.
- [ ] GitHub Actions build and unit tests pass.
- [ ] Published plugin index contains the new FilmMakinesi entry.
- [ ] User installs this exact build and verifies search, detail, playback/source switching and all subtitle tracks on a real device.

A successful build proves compilation and static parsing only. Live response shape, each player family, subtitle response validity and device playback remain unverified until the runtime checks above are completed.

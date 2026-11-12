# FilmMakinesi site profile

- Base URL: https://filmmakinesi.to/
- Provider module: FilmMakinesi
- Content scope: films and series
- Implementation: compact provider + one CloseLoad/Rapid JavaScript resolver
- Investigation status: CI BUILD/UNIT TESTS PASSED for source commit `c194e6a298f2f9285468a073a03e1e4a07b9b483` (workflow run `38158823038`); documentation PR CI also passed (run `38161466063`); DEVICE_TEST_PENDING
- Live site fetch from this automation environment: BLOCKED (HTTP 451; this does not establish that the user's device or CloudStream is blocked)
- Real-device CloudStream status: NOT TESTED

## Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| NeoWatch has a compact FilmMakinesi v7 plugin (~25 KB) | CONFIRMED | Published metadata in https://raw.githubusercontent.com/neoser1984/neowatch/builds/plugins.json |
| NeoWatch requests player iframes with film-page Referer and Sec-Fetch-Dest/Mode headers without forcing a desktop browser UA | CONFIRMED | Public source: https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/GizliJsOynatici.kt |
| Player variable extraction uses bounded Rhino evaluation, then legacy `file_link` Base64 fallback | CONFIRMED | NeoWatch resolver source above |
| Current CloseLoad `dc_hello` and `dc_<name>([...])` payload fallback | CONFIRMED at parser/test-fixture level | Regression fixture is included in `FilmMakinesiPlayerResolverTest`; not a live-site verification |
| Published builds index has a FilmMakinesi entry | CONFIRMED | Current `builds/plugins.json` entry reports version 29, file size 33,155 bytes, SHA-256 `705add1a1e217f847d7aa6dfbaafae3c23a4440deb1fa1324d89b5b4e357c42f`; this snapshot alone does not prove Android playback |
| Live FilmMakinesi HTML and player payload were fetched by this environment | NOT CONFIRMED | Tool returned HTTP 451 |
| Playback on a real CloudStream Android device | NOT TESTED | Requires an actual app/device session |

## Current request flow

1. Home/search/detail requests use the provider's standard FilmMakinesi HTML selectors and site referer.
2. `loadLinks()` reads the page's `data-video_url` entries and player iframe sources.
3. CloseLoad and Rapid-family players go through `FilmMakinesiPlayerResolver`; other embeds use CloudStream's registered `loadExtractor`.
4. The player is requested with the FilmMakinesi page as referer plus iframe navigation headers, while leaving CloudStream's default User-Agent unchanged.
5. The resolver looks first for a variable referenced by `sources: [{ file: variable }]`, evaluates relevant unpacked scripts in a time-limited Rhino context, and then tries old `file_link` and current `dc_hello` / chunk-array payloads.
6. Emitted stream links use the player origin as referer.

## Release gate

- [x] Provider reduced to the main provider plus one focused resolver.
- [x] Tests cover player-type detection, source-variable matching, and a known CloseLoad array-payload fixture.
- [x] CI build and unit tests passed for source commit `c194e6a298f2f9285468a073a03e1e4a07b9b483` (workflow run `38158823038`).
- [x] Documentation-only PR CI passed (workflow run `38161466063`); this validates repository tests/build, not live playback.
- [x] Current published index entry inspected: version 29, 33,155 bytes (hash above). Whether this is the intended newest provider build is not inferred from the index alone.
- [ ] User verifies source discovery and video playback in CloudStream on a real device.

Build success is not evidence that the currently live stream endpoint plays on every device. The site may change its payload or require further request details.

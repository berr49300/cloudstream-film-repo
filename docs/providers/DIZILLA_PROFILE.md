# Dizilla — Site Profile

> Status date: 2026-10-10. This file distinguishes evidence from source-code inspection and user/device observations. It does not claim live site/runtime behavior that has not been reproduced.

## 1. Status summary

- Site/base URL in provider code: https://dizilla.now
- Provider module: `Dizilla`
- Content in scope: TV series, anime, Asian drama
- Overall status: INVESTIGATING
- Live site access from external inspection environment: BLOCKED/NOT CONFIRMED (prior requests returned 403 according to engineering notes)
- Runtime status: VIDEO STARTUP REPORTED IMPROVED BY USER; SUBTITLES FAILING ON DEVICE; catalog rows still need verification
- Reference research: NeoWatch's public Dizilla provider and ContentX extractor were compared as prior art. This is not a runtime dependency.

## 2. Evidence ledger

| Claim | Status | Evidence | Remaining question |
| --- | --- | --- | --- |
| Search uses a JSON API | CONFIRMED IN CODE; LIVE RESPONSE NOT REPLAYED | Provider sends POST to /api/bg/searchContent with searchterm and Accept JSON header | Does the current live API still return the expected encrypted response? |
| Detail/episode data is read from embedded Next.js state | CONFIRMED IN CODE; LIVE RESPONSE NOT REPLAYED | Parser reads script#__NEXT_DATA__, props.pageProps.secureData and decrypts it | Does the current site response have the same state shape? |
| Episode source list is present in parsed data | CONFIRMED IN CODE; LIVE RESPONSE NOT REPLAYED | Model maps RelatedResults.getEpisodeSources.result.source_content | Are all source entries parsed for current episodes? |
| Source candidate extraction handles common HTML/JSON fields | CONFIRMED IN CODE | Shared SourceCandidateParser supports common iframe/media attributes and property names | Does each real source shape match the supported patterns? |
| ContentX/Pichive family is used by at least one observed source | USER-REPORTED CONTEXT / CODE PATH EXISTS | User identified Pichive; local extractor targets iframe.php with query key v | Which exact host/player chain does each current source use? |
| Subtitle callback is wired from provider to extractor | CONFIRMED IN CODE | loadLinks passes subtitleCallback into ContentXGenel and loadExtractor | Does callback fire for valid, accessible tracks in the failing playback? |
| Subtitle URLs are valid and downloadable | UNKNOWN | Parser unit tests validate synthetic examples only | Actual HTTP status, content type, body format and required headers are not verified |
| CloudStream renders the selected subtitle | FAIL (USER DEVICE OBSERVATION) | Screenshots show “Henüz altyazı yüklenmedi”; user reports the issue affects Turkish and English and is not limited to one show | Does player fail to fetch URL, reject payload/format, or lack required headers? |
| Every configured home/category row returns correct content | NOT TESTED | Selector logic exists; no successful live comparison available | Compare each row with its source page when accessible |
| NeO/BronzeCloud must remain available at runtime | NO — ARCHITECTURE REQUIREMENT | The repo owns its adapter and shared utilities; reference repos are research material only | Keep all required runtime logic in this repo / licensed dependencies |

## 3. Request/data flow inferred from current code

1. Home/category: GET public page, parse HTML card elements.
2. Search: POST /api/bg/searchContent with searchterm; read encrypted response and decode result JSON.
3. Detail/episodes: GET canonical page; read Next.js embedded state; decode secureData; model series details, seasons and episodes.
4. Source discovery: open episode page; decode embedded page data; read RelatedResults.getEpisodeSources.result.source_content.
5. Candidate discovery: shared SourceCandidateParser extracts common direct URLs and iframe URLs from HTML/JSON fragments.
6. Player resolution: direct media candidates are emitted; ContentX-like iframe.php?v= candidates are delegated to ContentXGenel; other iframe candidates use CloudStream loadExtractor.
7. ContentX: GET iframe page; parse potential subtitle declarations; resolve window.openPlayer ID through source2.php to get HLS media URL.
8. Subtitles: ContentXSubtitleParser looks for file/label, HTML track src/kind/label/srclang or src+language variants and sends found tracks to CloudStream's subtitle callback.

Steps 1–8 describe current code behavior, not a verified live network trace.

## 4. High-priority unresolved diagnosis

The user observes both English and Turkish subtitle labels but no loaded subtitle text. This suggests that parser discovery alone is insufficient. Diagnose each stage independently:

- [ ] Log count of candidate source URLs and extractor family (host/path only; redact query values).
- [ ] Log whether each subtitle callback is invoked and language label emitted.
- [ ] Verify subtitle URL host/path pattern without logging signatures or token query values.
- [ ] Check status/content type/body size and first bytes for an actual permitted subtitle request; never record session secrets.
- [ ] Confirm SRT/VTT/ASS/SSA format and encoding.
- [x] Code change applied: emitted subtitle tracks now receive a Referer header set to the ContentX iframe URL via CloudStream's SubtitleFile headers field (commit 245b47a). This is a hypothesis-driven fix; runtime success remains unverified.
- [ ] Check whether the actual subtitle server accepts that Referer and whether cookies or other permitted headers are also required.
- [ ] Compare one independently known-good subtitle file with one returned by the provider, to isolate player configuration vs provider URL.
- [ ] Confirm the exact track renders in CloudStream on device.

Do not change another parsing regex unless the observed payload demonstrates that its format is the mismatch.

## 5. Next implementation steps

1. Add sanitized fixture(s) for real response shapes when obtainable from a permitted, reproducible request.
2. Instrument source and subtitle stages with safe, structured logs.
3. Add tests for each newly observed response variant before changing parser behavior.
4. Fix the earliest stage supported by evidence; do not add site-specific workarounds to shared parsing without a second demonstrated use case.
5. Verify current CI build/unit tests, then separately verify actual playback/subtitle behavior in CloudStream.
6. Do not restore public distribution until the acceptance checklist is satisfied or the release is explicitly labelled with known limitations.

## 6. Boundaries

- Do not store credentials, cookies, private tokens, active signed media links or DRM material in this profile.
- Do not assume 403 means a parser bug; record access restrictions separately.
- Do not attempt to defeat DRM, CAPTCHA or access controls.
- NeoWatch and other repositories are research references only. This provider must not require their repository, API or infrastructure to be available.

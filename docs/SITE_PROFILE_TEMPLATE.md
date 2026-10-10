# Site Profile — <SITE_NAME>

> Copy this file to a site-specific profile before implementation. Replace placeholders. Use only sanitized evidence. Never commit cookies, credentials, private tokens or active signed playback URLs.

## 1. Status summary

- Site/base domain:
- Date/time and region tested:
- CloudStream version/device (if relevant):
- Investigator:
- Overall status: UNKNOWN / INVESTIGATING / BLOCKED / READY FOR IMPLEMENTATION / RUNTIME VERIFIED
- Public site reachable: YES / NO / PARTIAL
- Main restriction, if any:
- Content types in scope:

## 2. Evidence ledger

| Claim | Status (CONFIRMED / HYPOTHESIS / BLOCKED) | Evidence/link/fixture | Remaining question |
| --- | --- | --- | --- |
| Search data origin | | | |
| Catalog/category data origin | | | |
| Detail/metadata origin | | | |
| Episode list origin | | | |
| Playback source origin | | | |
| Player family | | | |
| Subtitle origin/format | | | |
| Access restrictions | | | |

## 3. Route and request map

| Action | Page/API route pattern | Method | Content type | Auth/session/headers needed | Paging | Status/evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Home | | | | | | |
| Category/genre | | | | | | |
| Search | | | | | | |
| Detail | | | | | | |
| Season/episode list | | | | | | |
| Episode source | | | | | | |
| Player iframe | | | | | | |
| Media manifest/file | | | | | | |
| Subtitle track/file | | | | | | |

## 4. Observed request flow

Write the actual chain; do not fill gaps with assumptions.

Example: Search UI -> POST /api/search -> JSON result -> detail page -> episode API -> iframe URL -> player config -> HLS manifest.

Actual flow:

1.
2.
3.
4.
5.

## 5. Response shape and field map

For each distinct response shape, save a sanitized fixture under the provider's test resources, if permitted.

| Data field / selector | Meaning | Required? | Example (sanitized) | Target model field |
| --- | --- | --- | --- | --- |
| | | | | |

Fixture paths:
- Catalog/search:
- Detail:
- Episodes:
- Source response:
- Player page/config:
- Subtitle response:

## 6. Player and media families

| Source label | iframe/direct URL pattern (redact IDs/tokens) | Player family | Required referer/origin | Media type (HLS/DASH/MP4/unknown) | Resolution steps | Status |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

Record every distinct player family. Do not assume all source labels share one player implementation.

## 7. Subtitles — separate from video

| Track label/language | Declaration location | URL pattern (no secrets) | Request headers required | Response status/content-type | Format/encoding | CloudStream rendered? |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

Debug stage:
- [ ] Track found in site/player response
- [ ] Subtitle callback invoked
- [ ] URL resolved against correct base
- [ ] Request succeeds (status and non-empty payload)
- [ ] Payload is valid SRT/VTT/ASS/SSA
- [ ] Language/encoding/timestamps valid
- [ ] Selected track renders in CloudStream on a real device

If a test is blocked, state why instead of marking it passed.

## 8. Access restrictions and safety boundaries

- HTTP status/redirect behavior:
- Challenge or CAPTCHA:
- Login/session/cookies:
- Rate limits:
- Region/network restrictions:
- DRM or other protected media:
- Permitted/documented interface used:
- What could not be verified:

Do not attempt to defeat DRM or access controls. Do not store secrets in this profile.

## 9. Test plan and release gate

| Test | Expected outcome | Result (PASS / FAIL / BLOCKED / NOT TESTED) | Evidence |
| --- | --- | --- | --- |
| Search finds known item | | | |
| Catalog matches source page | | | |
| Detail metadata maps correctly | | | |
| All seasons/episodes map correctly | | | |
| Direct episode link works | | | |
| Next-episode navigation works | | | |
| Each player family yields a link | | | |
| Fallback source works after a failed source | | | |
| Subtitles are emitted | | | |
| Subtitle URL and payload validate | | | |
| Subtitle text renders on device | | | |
| Unit tests and full build pass | | | |
| Package/index artifact is consistent | | | |

## 10. Implementation decision

- Shared utilities to reuse:
- Site-specific adapter responsibilities:
- New player extractor required:
- Regression fixtures/tests to add:
- Known limitations:
- Rollback plan:
- Ready to implement? YES / NO — explain:

## 11. Post-fix learning

- Symptom:
- Stage where it failed:
- Root cause backed by evidence:
- Change made:
- Regression test added:
- Build status:
- Runtime/device status:
- Follow-up notes:

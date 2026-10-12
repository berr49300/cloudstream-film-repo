# DDizi site profile

- Base domain: `https://www.ddizi.tel`
- Legacy alias: `https://www.ddizi.im` (public domain lookup says it redirects to `.tel`; keep it as an accepted alias for old links)
- Provider module: `DDizi`
- Status: **INVESTIGATING — playback/source chain not runtime-verified**
- Content scope: TV series, including foreign-series catalogue
- Date checked: 2026-10-12
- Device/runtime: User reports failures in CloudStream; this automation cannot read the failing stream URL from the screenshot.

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
| --- | --- | --- | --- |
| `.tel` is the current public homepage/domain | CONFIRMED | Current public index results show DDizi homepage and new episode pages on `ddizi.tel` | Direct HTTP behavior from the user's network can differ |
| `.im` redirects to `.tel` | REPORTED BY PUBLIC DOMAIN LOOKUP | [Public domain lookup](https://www.ipaddress.com/website/www.ddizi.im/) | Verify redirect from the affected device/network |
| Series pages use `/diziler/{id}/{slug}` | CONFIRMED | Repo parser/tests and pages linked from current public index | Verify response markup and all page variants |
| Episode pages use `/izle/{id}/{slug}.htm` | CONFIRMED | Repo parser/tests and currently indexed episode pages | Verify episode-page/player markup changes |
| Search uses POST `/arama/` with field `arama` | HYPOTHESIS FROM PRIOR PROVIDER CODE | Code and tests implement this route; no live response fixture is stored | Capture sanitized request/result from current site |
| Episode page exposes a usable source/player chain | BLOCKED | User screenshot shows `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)`; user previously reported a `/player/oynat/{id}` page returning 404 | Capture which source host/path returns the error and HTTP status |
| YouTube relay/player route is current | BLOCKED | Parser has synthetic tests for `/player/telif/index.php` and legacy `youtube.php`; these only prove parsing, not current-site availability | Inspect a current episode's actual source buttons and script configuration |
| Direct HLS URL is playable | FAIL / NOT VERIFIED | User screenshot reports HTTP-status playback failure with a source labeled `DDizi HLS` | Need redacted source hostname/path plus HTTP status; do not assume all HLS URLs are valid |
| 1080p and fallback YouTube links work in CloudStream | NOT TESTED | Unit tests cover quality selection only | Install candidate v6 and test on device |
| Correct season/episode mapping for foreign series | PARTIAL | Parser unit tests cover known title and finale examples | Compare at least one currently running foreign series against the live site |

## Request-flow map

1. Homepage/category and search return HTML pages (search route is still a hypothesis until a current response is captured).
2. Series details are linked through `/diziler/{id}/{slug}`; episode pages use `/izle/{id}/{slug}.htm`.
3. The provider scans iframe/video/source/meta tags, player-control links and selected inline script patterns.
4. Candidate URLs may be direct media/HLS, a DDizi player route, an external iframe or a YouTube relay.
5. A link is shown to CloudStream when an extractor resolves it or a media URL is detected. **Showing a link is not proof that its manifest/segments return successful HTTP responses.**

## Known failures and intended regression checks

- [ ] Foreign-series page and current-page pagination compared with live site.
- [ ] Search results compared with live site.
- [ ] At least one foreign series detail page and its season/episode list compared against source.
- [ ] At least one episode where HLS works.
- [ ] At least one episode where HLS fails but YouTube fallback works.
- [ ] YouTube 1080p and 720p fallback appear without duplicated quality labels.
- [ ] Each detected player family is tested separately.
- [ ] Source URLs and response headers are inspected only with query tokens redacted.
- [ ] Real-device playback confirmed.

## Root-cause notes

The v5 release constrained the resolver to at most two emitted links and selected only one YouTube format capped around 720p. This was too restrictive for the user's explicit 1080p + fallback requirement. It also included the quality in the link name even though CloudStream displays the quality field separately, causing duplicate labels. The v6 candidate keeps a small bounded list of variants instead of returning every format, accepts the legacy `.im` alias, and still leaves runtime playback marked unverified.

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

## Live source-route spot checks — 2026-10-12

The public episode pages themselves are reachable and list an explicit `video` source button, but the target of that button currently returns an actual HTTP 404 from the public site:

- Dark S3E8 source link: [`/player/oynat/f827901dcad74c34ebf7541c2bcb1377`](https://www.ddizi.tel/player/oynat/f827901dcad74c34ebf7541c2bcb1377) — **404 Not Found**.
- Sevdiğim Sensin E20 source link: [`/player/oynat/fd36424d05d9766cc1fff388ea494094`](https://www.ddizi.tel/player/oynat/fd36424d05d9766cc1fff388ea494094) — **404 Not Found**.
- The live foreign-series catalogue [`/yabanci-dizi-izle`](https://www.ddizi.tel/yabanci-dizi-izle) currently lists Dark, Berlin, Bridgerton, Elite, and other titles.

This confirms that at least these two player buttons are broken at the **site endpoint**, independent of CloudStream. It does **not** prove every stream or every HLS candidate is broken. The source URL behind the CloudStream label `DDizi HLS` is not visible in the screenshot, so the exact status of that separate media URL remains **UNKNOWN**. Do not label HLS as healthy just because a direct media URL was emitted, and do not label a dead player wrapper as playable. A source-level device log with hostname/path and query values redacted is needed to trace the exact HLS failure.

## Analysis process correction

The repository's discovery playbook and acceptance checklist already existed, but the initial DDizi v5 work did not produce a complete, evidence-backed DDizi profile before the release. This was a process miss. This profile now records the known routes and the concrete 404 checks; the unresolved player chain remains BLOCKED until a current working source family is evidenced.


## v7 investigation — 2026-10-12

- The live Teşkilat detail page currently lists 10 episodes per page (188–179 on the base route); pagination exposes historic pages, including a `/sayfa-0` route that duplicates the base page. See [live detail page](https://www.ddizi.tel/diziler/1856/teskilat-189-son-bolum-izle) and [page 1 link target](https://www.ddizi.tel/diziler/1856/teskilat-189-son-bolum-izle/sayfa-1).
- **Likely root cause for the exact Teşkilat screenshot:** the live `/sayfa-7` detail page contains episodes 118–109 and marks 111 as `Sezon Finali` ([live page 7](https://www.ddizi.tel/diziler/1856/teskilat-189-son-bolum-izle/sayfa-7)). If the provider starts with this paginated route, the previous code uses `/sayfa-7` as the parent series path; the sibling page links then fail the `candidatePath.startsWith("$seriesPath/")` test, so older finals such as 79, 48 and 14 are never seen. With only finale 111 detected, 109–111 all become Season 1. The screenshot's visible three entries exactly match that page's Season 1 group. The user's exact request URL is not in the screenshot, so confirm the initial route with v7's `stage=episode-pages route=... baseRoute=...` trace.
- The previous implementation also fetched only the first 8 pagination links and could spend one slot fetching duplicate `/sayfa-0`. v7 normalizes any paginated route back to the base series URL, fetches the base page when needed, ignores duplicate `/sayfa-0`, then fetches up to 20 historic pages in batches of four. It logs redacted route, HTTP status and duration for each page.
- v7 source diagnostics use the `DDiziTrace` log tag. Load and candidate logs record a query-redacted host/path, source family, extraction elapsed time, HTTP result where explicitly requested, found/selected qualities, skipped candidate limits, and which source families were emitted. Direct HLS links are marked `emit-unprobed`: emitting the URL does not verify its manifest or media segments.
- The source picker labels distinct families (for example `DDizi YouTube`, `DDizi HLS (host)`, or `DDizi external-host`) so the user can report which specific option plays. At most 12 candidates are visited and at most 6 links are shown, keeping the search bounded rather than probing endlessly.
- User device playback remains **NOT TESTED** from this automation environment. The automated build can validate Kotlin/parser behavior but cannot press play in the user's CloudStream app.

## What to inspect in the next device test

1. On a known series detail page, check the `DDiziTrace stage=episode-map` line: `pages`, `anchors`, `episodes`, `seasonFinals` and `seasonCounts`.
2. On a failing episode, check `stage=source-summary` plus preceding `candidate-attempt`, `youtube-extract`, `extractor`, `nested-page`, and `emit-unprobed` lines.
3. A working source is confirmed only when it begins playing and continues beyond initial buffering; an emitted link alone is not PASS.

# DDizi source and playback test matrix

This is the evidence ledger for keeping working source families and removing dead/slow attempts. Never mark a source PASS because a link merely appears in the source menu.

## Device test template

Copy one row per episode and source option. Do not paste a full signed playback URL. Keep only the episode title/number, visible source label, hostname if available, and outcome.

| Date | Series / episode | Visible source label | Outcome | Time to first frame | Error / note | Decision |
|---|---|---|---|---:|---|---|
| 2026-10-12 | Teşkilat 109–111 (user screenshot) | Episode list shows only 3 entries, Season 1 | FAIL — mapping | n/a | Historical season-final boundaries and accepted episode count not yet confirmed from device logs | Investigate pagination and episode filtering |
| 2026-10-12 | Daha 17 S1E18 (user screenshot) | `DDizi YouTube - 1920×1080` | BUFFER — user-observed | n/a | Picture appears but spinner remains; screenshot alone cannot distinguish 1080p bitrate/network, expired URL, or extractor/segment failure. Keep 1080p and its lower-quality fallback; do not treat this as proof that all YouTube methods are bad. | Compare with 720p on same episode and capture redacted `DDiziTrace` source timing before pruning resolvers |
| 2026-10-12 | Teşkilat 111 | iframe → `ddizi.re/player/youtube.php` | UNKNOWN — external fetch blocked | n/a | Public web fetch returned 403 on the cross-domain relay; this does not establish device behavior | Keep relay → YouTube resolver and verify on-device |
| 2026-10-12 | Dark S3E8 | DDizi `video` button | FAIL — site URL | n/a | The linked `/player/oynat/{id}` URL returned HTTP 404 in a public-site check | Do not treat this wrapper as playable; look for other current sources |
| 2026-10-12 | Sevdiğim Sensin E20 | DDizi `video` button | FAIL — site URL | n/a | The linked `/player/oynat/{id}` URL returned HTTP 404 in a public-site check | Do not treat this wrapper as playable; look for other current sources |

## Result definitions

- **PASS:** the selected source starts and keeps playing for at least 60 seconds.
- **BUFFER:** it starts but repeatedly stalls; record rough time to first frame and whether stalls continue.
- **HTTP FAIL:** CloudStream shows an HTTP status error. Record the status and source label; do not paste query tokens.
- **EXTRACT FAIL:** link resolution fails or no link is emitted before playback opens.
- **DECODER FAIL:** the source URL responds but CloudStream reports a codec/decoder error.
- **NOT TESTED:** no device playback test was made.
- **BLOCKED:** the site's own player URL is dead, a network denies access, or evidence is unavailable.

## Safe runtime diagnostics

The v7 provider emits diagnostic lines under the `DDiziTrace` and `DDizi` log tags. These records intentionally contain host/path only and omit URL query parameters.

With Android platform-tools and the device connected by USB:

```sh
adb logcat -c
# Reproduce one detail-page issue and one playback issue in CloudStream.
adb logcat -d -s DDiziTrace:D DDizi:D *:S > ddizi-diagnostics.txt
```

Share only `ddizi-diagnostics.txt`, not a complete unfiltered system log. It should show page counts, historic season-final numbers, candidate families, elapsed times, HTTP status where checked, qualities found, and which links were emitted. It does not certify that an emitted media URL actually plays.

If ADB is unavailable, use the visible source labels as the test IDs: select each option once and record PASS / BUFFER / HTTP FAIL / EXTRACT FAIL / DECODER FAIL. This creates a reproducible record rather than repeatedly changing the resolver based on memory.

## How we prune the resolver

1. Keep a method when it produces at least one device-confirmed PASS.
2. Keep a failed method temporarily only if diagnostics show it is required to reach another candidate or its failure is intermittent.
3. Remove methods that repeatedly add delay and produce no links, after checking at least two representative episodes.
4. Keep separate results for domestic and foreign series; do not assume they share the same source chain.
5. Re-run parser tests and the full build after every removal. Preserve the evidence in this matrix.

## Current verification boundary

Automated unit/build tests and public website checks run in GitHub Actions/web research. This environment cannot directly install or operate the user's CloudStream Android app, so actual playback and buffering require one device test cycle.

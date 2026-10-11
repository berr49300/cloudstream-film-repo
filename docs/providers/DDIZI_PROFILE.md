# DDizi Site Profile

Updated: 2026-10-11

## Status summary

- Base domain: https://www.ddizi.tel/
- Overall status: INVESTIGATING
- User-reported device result: FAIL — the series detail shows unrelated episodes and no video starts.
- Search: NOT TESTED / intentionally unsupported until the actual form request is confirmed.
- Current code package version: 2, experimental status (3).
- Real CloudStream playback after the proposed fix: NOT TESTED.

## Confirmed route and page behavior

- Series/“latest episode” landing route: `/diziler/{id}/{slug}`.
- Episode route: `/izle/{id}/{slug}.htm`.
- Series page example: https://www.ddizi.tel/diziler/2160/sevdigim-sensin-son-bolum-izle
  - The top content area lists episodes 20 through 11 for Sevdiğim Sensin.
  - The pagination includes an older page at `/sayfa-1`, which lists episodes 10 through 1.
  - After the series episode list the page has a separate “Bugünkü Bölümler” area with episodes from other series.
- Episode page example: https://www.ddizi.tel/izle/91892/sevdigim-sensin-20-bolum-izle-hd2.htm
  - It has an explicit “Sevdiğim Sensin dizi sayfasına git” link.
  - It has player-part controls and a visible player route under `/player/oynat/{redacted-token}`.
  - It has a “Dizinin Diğer Bölümleri” section, followed later by the unrelated “Bugünkü Bölümler” list.

## Evidence ledger

| Claim | Status | Evidence / remaining gap |
| --- | --- | --- |
| Series route and page contains actual episode links | CONFIRMED | Public series route above. |
| The page contains unrelated episode links outside the series list | CONFIRMED | “Bugünkü Bölümler” appears after the series episode list; the entries name different series. |
| Series episode pagination uses `/sayfa-N` | CONFIRMED | Public route `/sayfa-1` returned episodes 10 through 1. Further page count should be derived from real pagination links. |
| Episode page links back to the correct series | CONFIRMED | Explicit “dizi sayfasına git” link on the Sevdiğim Sensin episode page. |
| Player wrapper route | CONFIRMED | The visible iframe/player link uses the same-site `/player/oynat/{token}` route. |
| Player wrapper response through the external web inspector | BLOCKED | Opening the observed wrapper link returned HTTP 404 in the inspection environment. It may depend on browser/referrer context; this does not prove the actual CloudStream request fails identically. |
| Some historical player pages use `ddizi.re/player/youtube.php?id=...` | CONFIRMED for a historical page only | Do not assume every current episode uses this player family. |
| Current media URL type (HLS, MP4, DASH, YouTube, or other) | NOT TESTED | The actual current wrapper response and returned media have not yet been verified. |
| Search request method and field | BLOCKED / NOT CONFIRMED | Search box is visible but its submission request has not been captured; do not guess a POST/API endpoint. |
| Subtitles | NOT TESTED | No current track declaration or successful device playback has been verified. |

## Diagnosed failures and code changes under test

1. Episode association bug: the first implementation scanned every `/izle/` anchor on the series page, including the unrelated “Bugünkü Bölümler” list. The proposed fix normalizes the series title and retains only episode labels that contain that normalized title.
2. Missing older episodes: the series page has a visible `/sayfa-1` route. The proposed fix follows only pagination URLs under the current series path and combines their episode links with the first page.
3. Player discovery bug: the first implementation explicitly rejected all same-domain `ddizi.tel` URLs, including the site's own `/player/oynat/{token}` wrapper. The proposed fix allows only that observed same-site player route, follows a bounded nested iframe/meta-player chain, and maps the specifically observed `ddizi.re/player/youtube.php?id=...` form to a YouTube watch URL. Other sources are still passed to registered CloudStream extractors.
4. Poster selection: use the observed poster image selectors before falling back to the generic Open Graph image, which can be the site logo.
5. The DDizi module was marked experimental until runtime evidence confirms the fix.

## Regression tests

- Series title “Sevdiğim Sensin Son Bölüm izle Full” cleans to “Sevdiğim Sensin”.
- Episode label “Sevdiğim Sensin 20.Bölüm” is associated with that series.
- “Ömür Usta 3.Bölüm” and “Mercan Köşk 6.Bölüm” are excluded from that series.
- The observed DDizi YouTube relay URL maps to a YouTube watch URL only for the `ddizi.re` host.
- Build/test status for the proposed fix: pending CI until its workflow completes.
- CloudStream real-device episode list and playback after the fix: NOT TESTED.

## Required next checks

- Verify CI unit tests and plugin packaging.
- Confirm the generated version-2 artifact and published manifest hash/size agree.
- On a real CloudStream device, confirm the series title/poster, all episodes across pages without cross-series entries, source emission, actual video+audio playback, and subtitles independently.
- If sources remain empty, request only the specific failing episode/page and sanitized player response or CloudStream debug log necessary to identify the next hop. Never store cookies, signed media URLs, or private tokens in this repository.

# Haty — unified provider integration

## Purpose

Haty is the working name for this repository-side integration effort. The aim is to keep independently implemented CloudStream providers in one repository and one repo feed, rather than force unrelated sites into a single brittle `MainAPI`.

## Source inventory

The supplied APK previously identified as THY TV 5.0 was inspected statically. Its class/string inventory suggests these provider families:

| Provider family | Current repository status | Next step |
|---|---|---|
| FilmMakinesi | Already exists | Preserve; no duplicate |
| Dizilla | Already exists | Preserve; no duplicate |
| FullHDFilmizlesene | Already exists | Preserve; no duplicate |
| Dizipal | Not yet integrated from this inventory | Verify public current routes and page/player flow before implementation |
| Diziyou | Not yet integrated from this inventory | Verify public current routes and page/player flow before implementation |
| Turkish123 | Candidate for a separate provider | Public pages inspected; HTML selectors and source/playback flow still need verification |
| Canlikolik | Candidate live-TV provider | Verify public channel list and permitted stream metadata |
| tabii | Candidate live-TV provider | Verify official public catalogue/channel flow and permitted playback interface |
| CanlıTribün | Candidate sports provider | Remote base URL was not confirmed from static APK strings |
| Papaz | Candidate sports provider | Remote base URL was not confirmed from static APK strings |

## Turkish123 reconnaissance — 2026-10-12

Public pages observed:
- Home: `https://ahs.turkish123.com/`
- Series list: `https://ahs.turkish123.com/series-list/`
- Example detail: `https://ahs.turkish123.com/anne-yarisi/`

Confirmed from public page output:
- The site exposes Home, Series, Episodes, Calendar and Genre navigation.
- The series list contains links to individual series pages.
- The example detail page exposes season/episode links, title, synopsis, genre, cast, duration, quality, year and status.
- The detail page includes a YouTube trailer iframe and episode/content controls.
- The site states that content is provided by non-affiliated third parties; the page itself does not establish that every media source is authorized or directly hosted by the site.

Not yet confirmed:
- The exact stable HTML selectors in raw response markup.
- Search request behavior and pagination rules.
- The episode-to-player request chain and the response shape for video sources.
- Whether any source is directly playable in CloudStream, what headers are required, and whether subtitles are declared.
- Any real-device playback result.

## Implementation policy

1. Use a separate Gradle module for each independent provider; the existing `settings.gradle.kts` auto-discovers directories with `build.gradle.kts`.
2. Do not copy a third-party app wholesale or reuse its private keys, tokens, cookies, or signed media URLs. Implement from independently verified public behavior and preserve any required license/attribution for code that is lawfully reused.
3. Do not invent selectors, API schemas, endpoints, source URLs, or headers. If a route is blocked or dynamic behavior cannot be confirmed, mark it BLOCKED and add only deterministic parsing tests based on sanitized evidence.
4. Do not bypass authentication, CAPTCHA, DRM, certificate pinning, or other access controls.
5. Keep existing protected providers untouched unless separately requested.
6. Run the documented unit tests and Gradle build when an execution environment is available. Build success does not prove playback.
7. Do not publish to `builds` without explicit release approval.

## Current status

- Repository convention and protected-scope rules reviewed.
- Static APK inventory recorded.
- Turkish123 public home, series list and one detail page inspected.
- Provider implementation: NOT STARTED; stable raw HTML selectors and player flow still need evidence.
- CloudStream playback: NOT TESTED.
- No release published.
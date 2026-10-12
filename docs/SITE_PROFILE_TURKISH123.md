# Turkish123 Site Profile

## Status summary
- Base URL observed: https://ahs.turkish123.com/
- Status: INVESTIGATING
- Public pages inspected: home, series list, one series detail page.
- CloudStream runtime: NOT TESTED
- Provider implementation: NOT STARTED
- Scope candidate: Turkish/Asian drama series and episodes.

## Evidence ledger

| Claim | Status | Evidence | Remaining question |
|---|---|---|---|
| Home has navigation for series, episodes, calendar, genres and search | CONFIRMED | https://ahs.turkish123.com/ | Determine data source and pagination |
| Series list links to individual series | CONFIRMED | https://ahs.turkish123.com/series-list/ | Determine card markup and pagination |
| Detail page shows synopsis, genre, actors, duration, quality, year and status | CONFIRMED | https://ahs.turkish123.com/anne-yarisi/ | Map fields to CloudStream models |
| Detail page exposes episode/content links | CONFIRMED | Example detail page above | Verify all seasons, episode numbering and direct links |
| Search request/response format | UNKNOWN | Search field visible on home page | Inspect real request and response |
| Catalog and detail HTML selectors | UNKNOWN | Rendered public page only | Inspect raw HTML/DOM and scripts |
| Playback source chain | UNKNOWN | Not traced | Inspect player and public source configuration |
| Subtitle tracks and format | UNKNOWN | Not verified | Inspect actual player/source response |
| Required headers, cookies, or region limits | UNKNOWN | Not verified | Test normal public access; do not bypass controls |

## Routes observed

| Action | URL | Status |
|---|---|---|
| Home | https://ahs.turkish123.com/ | Inspected |
| Series list | https://ahs.turkish123.com/series-list/ | Inspected |
| Series detail example | https://ahs.turkish123.com/anne-yarisi/ | Inspected |
| Search | Form visible on home page; route not verified | Unknown |
| Episode/player | Not traced | Unknown |

## Implementation gates

1. Capture sanitized raw HTML for the home page, series list, detail page, and a representative episode/player page.
2. Identify actual selectors or embedded public data for catalog, search, detail metadata, and episodes.
3. Verify episode numbering against visible source pages.
4. Trace public player/source behavior and subtitle declarations without committing signed URLs, cookies, tokens, or private data.
5. Add deterministic parser tests from sanitized fixtures.
6. Build the module and separately mark CloudStream playback/subtitle tests as NOT TESTED until actually exercised.

## Decision

**Not ready for provider implementation yet.** Visible rendered pages establish the page features, but do not establish selectors, data endpoints, player resolution, or subtitle behavior. Do not guess these details.

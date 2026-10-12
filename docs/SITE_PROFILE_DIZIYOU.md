# DiziYou — Site Discovery Profile

**Status:** DISCOVERY IN PROGRESS  
**Site:** https://www.diziyou.one/  
**Profile date:** 2026-10-12  
**Provider implementation:** NOT CREATED  
**Live site contract:** PARTIALLY BLOCKED  
**CloudStream playback:** NOT TESTED

This document records observable evidence and separates it from hypotheses. It is not proof that a provider or playback path works.

## 1. Evidence and access status

| Item | Status | Evidence / notes |
|---|---|---|
| Canonical candidate domain | CONFIRMED (public references) | The site is indexed and referenced as `https://www.diziyou.one`; the domain also appears in public DiziYou posts. |
| Direct fetch from this research environment | BLOCKED | Opening `https://www.diziyou.one/` returned a web-fetch error (502 Bad Gateway). This does **not** prove that the site is down for normal browsers or that its HTML is empty. |
| Indexed homepage sections | CONFIRMED (search-index evidence only) | Public indexed content exposes sections resembling “Öne Çıkan”, “Son bölümler”, “Son eklenenler”, and “Trend içerikler”. Search indexing is not a substitute for inspecting the raw response. |
| Site-rendering technology | NOT TESTED | No raw homepage HTML/DOM was available in this pass. Do not assume WordPress, server-rendered HTML, an API, or a JavaScript framework. |
| Search request/response | NOT TESTED | Search route and exact query encoding/DOM selectors remain unknown. |
| Series detail and episode DOM | NOT TESTED | Exact selectors, season grouping, episode IDs, and episode URLs remain unknown. |
| Player iframe / source chain | BLOCKED / NOT TESTED | No current episode HTML or player response was inspected. |
| Subtitles | NOT TESTED | Languages, subtitle URL patterns, formats, and whether they are episode-specific remain unverified. |
| Direct playable media | NOT TESTED | No stream URL, manifest response, segment request, required headers, or device playback was validated. |

### Public references consulted

- DiziYou homepage indexed by search: https://www.diziyou.one/
- Site description / technology listing: https://w3techs.com/sites/info/diziyou.one
- Public posts linking to current DiziYou URLs: https://telemetr.io/en/channels/1506314203-diziyoucom

Search-index text shows examples of series cards and episode listings, but it does not expose the underlying DOM selectors or prove the current player contract.

## 2. Observable product behavior

Based on indexed public homepage content, the site presents itself as a Turkish-language series catalogue with Turkish dubbing and subtitle options. The indexed home content includes:
- Featured titles (“Öne Çıkan”).
- Recently added episodes (“Son bölümler”).
- Recently added titles (“Son eklenenler”).
- Trending content (“Trend içerikler”).
- Series cards with titles and, for some entries, ratings, year, description, and season/episode labels.

**Important:** these are observations from indexed page text. Exact HTML elements, card links, pagination, metadata fields, and whether every section is present in a live response are not yet confirmed.

## 3. Existing third-party provider research

A public CloudStream repository contains a DiziYou provider source:
- Repository: https://github.com/feroxx/Kekik-cloudstream
- File: https://github.com/feroxx/Kekik-cloudstream/blob/master/DiziYou/src/main/kotlin/com/keyiflerolsun/DiziYou.kt

That source is useful as a **research lead**, not as proof of current behavior. It suggests that an older implementation used selectors such as:
- Home section headings: `div.title`
- Series cards: `div#list-series-main`
- Search cards: `div.incontent div#list-series`
- Detail title: `h1`
- Episode cards: `div.bolumust`
- Player iframe: `iframe#diziyouPlayer`
- Option labels: `span.diziyouOption`

It also constructs candidate stream/subtitle URLs under a `storage` host. **None of these selectors or URL patterns are confirmed for the current site in this discovery pass. Do not implement them blindly.** The third-party source's license and attribution requirements must be checked before copying or adapting code. Prefer a clean implementation from verified site behavior.

## 4. Architecture classification

| Layer | Current classification | Next evidence needed |
|---|---|---|
| Home/catalogue | Unknown; indexed HTML text exists | Raw response, DOM, section/card structure, pagination behavior |
| Search | Unknown | Actual search request URL/method, encoded query, result structure |
| Series details | Unknown | Representative current series page HTML and metadata mapping |
| Episode list | Unknown | Episode links, season labels, ordering, duplicate handling |
| Player selection | Unknown; third-party source hints at an iframe and option controls | Current episode page HTML, iframe URL and iframe response |
| Media resolution | Unknown | Player config/API/JS, media URL and response headers |
| Subtitles | Unknown | Subtitle controls, real URLs, content type/format and response |
| CloudStream integration | Kotlin `MainAPI` provider is the repository convention | Implement only after the site contract is confirmed |

## 5. Required next discovery steps

1. Retry the homepage and representative series/search/episode pages using ordinary public access. If the research environment continues returning 502/403, record the exact block and do not infer selectors from it.
2. Run the repository diagnostic against accessible pages:
   ```bash
   python tools/provider_probe.py --url "https://www.diziyou.one/" --json
   ```
   If a page is accessible in an authorized browser but not in this environment, save sanitized HTML and use `--html-file` with `--base-url`. Do not include cookies, tokens, or signed media URLs in committed fixtures.
3. Capture one representative search result, one series detail page, and one episode page. Record real element selectors and the series/season/episode mapping.
4. Follow the player chain step by step: episode page → iframe/player configuration → candidate media → manifest/media response. Keep required referer/origin headers only when observed and tested.
5. Check subtitle URLs and payloads independently from video playback. Verify language, format, response status, and time alignment where possible.
6. Add sanitized regression fixtures and deterministic parser tests for each confirmed structure.
7. Implement the smallest Kotlin `MainAPI` provider consistent with this repository. Reuse the standard CloudStream extractor for supported players instead of creating a custom resolver without evidence.
8. Run `gradle testDebugUnitTest make makePluginsJson` and `python -m unittest discover -s tools/tests -v` when the environment allows.
9. Test the resulting extension in CloudStream: home sections, search, details, episode order, source list, playback, and subtitles separately.
10. Do not publish to `builds` until the implementation has passed the required checks and publication is explicitly requested.

## 6. Acceptance matrix

| Capability | Current result |
|---|---|
| Homepage catalogue | NOT TESTED |
| Pagination | NOT TESTED |
| Search | NOT TESTED |
| Series details and poster | NOT TESTED |
| Season/episode mapping | NOT TESTED |
| Player iframe extraction | NOT TESTED |
| Source URL resolution | NOT TESTED |
| HTTP headers / manifest access | NOT TESTED |
| CloudStream video playback | NOT TESTED |
| Turkish subtitle playback | NOT TESTED |
| English subtitle playback | NOT TESTED |
| Build and unit tests for a DiziYou module | NOT TESTED — module not yet created |
| Publication to `builds` | NOT PUBLISHED |

## 7. Decision

The correct next step is **evidence collection**, not coding guessed CSS selectors or guessed storage URLs. The public index establishes that DiziYou has a catalogue and episode listings, but this environment did not provide the current raw site response or player chain. The provider should remain unimplemented until those contracts can be verified.

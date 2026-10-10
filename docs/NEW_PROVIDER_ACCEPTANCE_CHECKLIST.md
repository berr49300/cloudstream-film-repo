# New Provider Acceptance Checklist

**Goal:** Start each new site with a repeatable, evidence-driven investigation so core capabilities are not discovered missing only after a provider has been installed. Reuse the engineering process and common utilities; do not blindly reuse another site's selectors, APIs, or extractor code.

A status is only **PASS** when there is evidence from the actual site and CloudStream. Build success alone is a **BUILD PASS**, not a runtime pass. Use **BLOCKED** when a site denies access or the available environment cannot verify it; never silently convert unknowns to passes.

**Before starting a new provider:** follow [SITE_DISCOVERY_PLAYBOOK.md](SITE_DISCOVERY_PLAYBOOK.md), then fill [SITE_PROFILE_TEMPLATE.md](SITE_PROFILE_TEMPLATE.md). Inspect public routes, scripts, embedded state and APIs before asking the user for sample links. Ask for user evidence only to fill a clearly named gap (device-only, region-specific, session-only or unavailable live response). Never infer an API from a URL alone.

## Gate 0 — Scope, access and reproducibility

- [ ] Record site base URL, date/time tested, locale/region, access result, redirects and whether a browser or device is needed.
- [ ] Confirm the content types in scope: series, anime, Asian drama, movies, live content, etc.
- [ ] Record whether login, cookies, JavaScript, API tokens, signatures, CAPTCHA, rate limits or regional restrictions are involved.
- [ ] Save sanitized request/response samples and representative URLs. Do not commit session cookies, credentials, private tokens or signed playback URLs.
- [ ] Distinguish site/network restrictions from a code defect. If live access is blocked, label affected checks BLOCKED.

## Gate 1 — Site reconnaissance before implementation

- [ ] Map the home page, every category route, genre pages, search, detail pages, season/episode pages and player/source routes.
- [ ] Identify the true data source for each path: server-rendered HTML, embedded JSON, public API, paginated API or JavaScript-generated data.
- [ ] Inspect at least one example of each distinct response shape; do not infer one site's structure from another provider.
- [ ] List the card wrapper selectors and the relationship between poster, title, URL, type label and metadata.
- [ ] Identify all source player families and their request chains (iframe -> API -> manifest, direct media, etc.).
- [ ] Record which facts are confirmed and which remain hypotheses.

## Gate 2 — Catalog and search

- [ ] Home page returns real items for every configured row; no empty rows caused by unsupported selectors or route assumptions.
- [ ] Each category is compared against the corresponding site's web page, not just checked for non-zero results.
- [ ] Genre pages are not assumed to have one media type. When a genre can contain anime or Asian drama, infer type using verified metadata/route rules and test exceptions.
- [ ] Search returns the correct canonical title, URL, year, poster and score where available.
- [ ] Pagination behavior is verified; if the source site cannot paginate, the limitation is explicitly documented.
- [ ] Duplicate cards are removed without collapsing different editions/languages incorrectly.
- [ ] Empty, blocked and malformed responses fail safely and are logged with enough context to diagnose.

## Gate 3 — Detail pages and episode mapping

- [ ] Title, synopsis, poster, background, year, score, tags, actors and runtime are verified against source pages where available.
- [ ] Every listed season and episode has the correct season number, episode number, language/dub label and canonical playable episode URL.
- [ ] Series detail slugs and episode slugs are not confused; "latest episode" links are mapped to the correct series when needed.
- [ ] Duplicates, specials, fractional episodes and missing numbering are handled deterministically.
- [ ] Test a middle episode directly and via previous/next episode navigation; both must resolve the intended episode.
- [ ] If the provider can return partial episode data, missing data is logged rather than silently fabricated.

## Gate 4 — Sources, extraction and playback

- [ ] Capture all source candidates with source label/language and host only (redact tokens/signatures from logs).
- [ ] For each player family, trace the actual request sequence and required headers/referer/cookies.
- [ ] Validate direct HLS (.m3u8), DASH (.mpd), MP4 and iframe cases that actually occur on the site.
- [ ] Each candidate records elapsed time, outcome (link emitted/no link/error/timeout) and extractor family.
- [ ] Slow/unavailable sources cannot stall the entire list indefinitely; use bounded, measured timeouts where the APIs permit.
- [ ] Do not parallelize suspending extractors or skip sources blindly. Preserve callback safety and source ordering semantics.
- [ ] Verify each emitted link opens in CloudStream. Distinguish link-resolution failures from unsupported codecs, resolution, bit depth or device decoder errors.
- [ ] Verify source fallback and ensure a failed first source does not prevent later usable sources from appearing.

## Gate 5 — Subtitles (independent release gate)

- [ ] Find out whether subtitles are declared in the player page, source API, media manifest or returned by an extractor.
- [ ] Confirm the subtitle callback is actually invoked for each expected track; a label visible in a menu is not proof that a subtitle file loaded.
- [ ] Normalize relative/protocol-relative URLs using the correct host and preserve required request headers when supported.
- [ ] Inspect the actual response: HTTP status, content type, non-empty payload and valid SRT/VTT/ASS/SSA format.
- [ ] Verify encoding, timestamps, language naming, forced subtitles and duplicates.
- [ ] Select every advertised test track in CloudStream and confirm text is visible at the right time on a real device.
- [ ] Test at least one subtitle file from each distinct player family; test a known-good subtitle file separately to isolate player settings.
- [ ] If a track fails, record exactly which stage fails: not found, not emitted, URL inaccessible, invalid format/encoding, header/cookie issue or player rendering.

## Gate 6 — Performance and resilience

- [ ] Record network and parsing duration separately for catalog, detail, source API, iframe extraction and first emitted link.
- [ ] Measure representative cases more than once and compare before/after timings.
- [ ] Ensure expected error/timeout behavior for slow hosts; no unbounded loading loop.
- [ ] Test empty responses, changed/missing JSON fields, broken links and one failed source among several valid sources.
- [ ] Optimize the measured bottleneck; do not confuse device decode errors with a slow provider.

## Gate 7 — Build, packaging and real-device acceptance

- [ ] Unit tests cover parsing fixtures for catalog, detail, episodes, source candidates and subtitles.
- [ ] Build/package succeeds and plugin manifest version/hash/index agree.
- [ ] Install the exact generated artifact into CloudStream on a real device.
- [ ] Test home rows, each content category, search, detail page, season/episode list, direct episode open, next episode, source switching and subtitles.
- [ ] Attach evidence (sanitized logs/screenshots, test date, artifact commit) to the release record.
- [ ] All required checks are PASS, or release is explicitly labelled experimental with known failures listed.

## Gate 8 — Release hygiene

- [ ] Source code and distribution artifacts have an intentional visibility plan (private source / public release index if needed).
- [ ] A test build is not advertised as production-ready.
- [ ] Version is incremented for a user-visible code change and the published manifest is verified.
- [ ] Rollback steps are known; distribution can be paused without deleting source history.
- [ ] Document every discovered failure with: symptom -> evidence -> root cause -> fix -> regression test -> outcome.

## Required per-site test report

| Area | Status (PASS / FAIL / BLOCKED / NOT TESTED) | Evidence / notes |
| --- | --- | --- |
| Live site access and response shape |  |  |
| Home/catalog rows |  |  |
| Genre/type correctness |  |  |
| Search |  |  |
| Detail metadata |  |  |
| Season/episode mapping |  |  |
| Direct + next episode navigation |  |  |
| Source discovery and fallback |  |  |
| First link latency |  |  |
| Subtitle callback |  |  |
| Subtitle response/format |  |  |
| CloudStream subtitle rendering |  |  |
| Codec/device compatibility |  |  |
| Build/unit tests/package |  |  |
| Real-device install/playback |  |  |
| Release visibility/rollback |  |  |

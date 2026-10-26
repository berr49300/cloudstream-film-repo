# Provider Engineering Notes

This file records reusable lessons from provider debugging so future site integrations can build on verified findings instead of repeating trial-and-error.

## Dizilla — current investigation (2026-10-10)

### Confirmed
- Source repository: `berr49300/cloudstream-film-repo`, provider module: `Dizilla`.
- Commit `91c8ca019552cdf470d841d17a9f4e6b20ec989b` builds successfully in GitHub Actions run [38041909911](https://github.com/berr49300/cloudstream-film-repo/actions/runs/38041909911).
- The latest provider code narrows category-card selection and sorts episode entries by season/episode.
- `loadLinks` currently resolves source candidates sequentially with `candidates.forEach`; a slow source can delay attempts to later sources.
- The ContentX extractor has a dedicated subtitle-parsing regex and invokes CloudStream's subtitle callback for matches.

### User-observed symptoms (not yet resolved)
- Opening a video/source previously remained on a spinner or took a long time. The user now reports that the opening-speed improvement is successful in their latest test; the exact root cause and timing have not been instrumented.
- Subtitle menu can show options such as Turkish, but selecting them does not produce visible subtitles. The player explicitly reports “Henüz altyazı yüklenmedi” (subtitle has not loaded). A new parser now handles normal/escaped track metadata and relative URL resolution with unit tests; device-level rendering remains unverified.
- Directly opening an episode can behave differently from advancing to it with the next-episode control.
- The latest screenshots show some dedicated category rows (Yabancı Diziler, Anime, Kore Dizileri) as empty, while some genre rows can contain titles whose media type is unexpected. The old selector required one specific wrapper and could reject sibling-poster cards; catalog selection has been broadened to multiple card wrappers with sidebar filtering. Live-page structure is still unverified because external requests return 403.

### Working hypotheses — verify before changing code
1. Slow playback startup may be caused by sequential extractor requests, slow/unavailable hosts, or source-page network delays. Measure per-source duration and success before optimizing.
2. Subtitle failure may occur at one of several stages: subtitle URL not extracted, callback not emitted, URL malformed/inaccessible, subtitle format unsupported, or player track selection issue. Log/verify each stage separately.
3. Episode navigation differences may involve episode URL/data mismatch or source-page differences; sorting alone does not prove this is fixed.
4. Category mixing may be caused by overly broad DOM selectors and incorrect `TvType` assignment. Verify actual site markup and route names before tightening selectors.

### Debugging protocol for this provider and future sites
- Separate build verification from real-world playback verification; a successful build does not prove the site works.
- Record exact failing title, season/episode, source name, source URL host (avoid logging secrets/tokens), duration, extracted links, and whether subtitle callbacks fire.
- Test one representative episode from each category, direct episode opening and next-episode navigation, at least two sources if available, and each advertised subtitle track.
- Prefer bounded timeouts, deduplication, clear logs, and measured optimizations. Do not introduce concurrency or skip sources blindly.
- Do not treat a subtitle menu label as proof that subtitle media is actually attached and playable.
- Re-check live site markup/API when selectors or response formats change; external requests may be blocked, so distinguish unverified hypotheses from confirmed facts.
- Keep site-specific parsing separate from reusable source/iframe extraction utilities where possible.

### Release and repository visibility
- Current source module version: `4` (test build). Public distribution is paused: `builds/plugins.json` is `[]` and the published `.cs3` artifact was removed.
- Repository is currently public. Making the whole repository private would also make the published `builds` branch and raw plugin artifacts inaccessible to unauthenticated CloudStream clients. If source privacy is required while distributing a working plugin, use a private source repository and a separate public distribution repository/branch with only the release artifacts and required index.
- Changing a public repository to private does not make existing public forks private. Review GitHub's visibility-change warnings before proceeding.

### Status
- Performance root cause: not confirmed.
- Subtitle root cause: not confirmed.
- Direct-vs-next episode discrepancy: not confirmed.
- Category selector/type fix: compiles, but still needs in-app validation.

## Additional findings and changes (2026-10-10)

### Subtitle parser correction
- The previous ContentX track matcher only recognized one exact escaped-quote sequence with no whitespace and with `file` before `label`.
- Added `ContentXSubtitleParser`, which normalizes quote/slash/Unicode escapes, supports either field order, resolves relative URLs against the iframe page URL, rejects non-HTTP(S) URLs, and deduplicates by URL.
- Added unit tests for normal JSON, escaped quotes/slashes/Unicode labels, reversed field order, duplicates, and unsafe schemes.
- This is a targeted fix to a code-level weakness, not proof that the site's actual subtitle payload matches the tested examples. Runtime subtitle loading is still an acceptance blocker until the actual player response and device rendering are verified.

### Catalog correction
- Broadened card discovery from a single `span.prm-borderb` shape to supported card wrapper variants and image/title lookup in the card parent. Added exclusions for common sidebar/navigation/recommendation containers.
- This implementation is based on the app screenshots and observed code paths; because the live site currently returns HTTP 403 to the external inspection environment, it still needs an in-app comparison against the site.
- Dedicated Anime/Korean routes map to their corresponding CloudStream types. Generic genre pages currently default to TvSeries; whether a genre's cards can be mixed types must be verified from actual item metadata before considering type labeling complete.

### Independent implementation and reference provenance
- The earliest Dizilla implementation was bootstrapped by studying/adapting parts of the public Neowatch/Dizilla approach, including the encrypted page-data/search pattern and some source-resolution ideas. It would be inaccurate to claim that the first version was built without that reference.
- A reference is not a permanent dependency: future providers should start from the acceptance checklist, capture the target site's own response shapes, and implement site-specific parsing against evidence. Reuse only the generic project-owned utilities where appropriate; do not copy a provider wholesale or assume selectors/API schemas carry over.
- The lasting project memory is stored in this repository's engineering notes and acceptance checklist. This is documentation for future tasks, not a claim that the underlying model is retrained or permanently changes its weights from one conversation.

### Distribution pause
- The root `repo.json` has been changed to an empty plugin list, and the README no longer instructs users to install the repository. Once the publish workflow finishes, the standard repository URL should present no plugin entries to new users.
- The GitHub repository itself remains public; hiding its page/source URL requires changing its repository visibility in GitHub Settings. This GitHub connector cannot perform that visibility setting change.
- Current provider speed report: user-confirmed improvement, root cause not benchmarked.
- Current subtitle status: code-level parser strengthened; runtime still not confirmed.
- Current catalog status: selector broadened; in-app categories still require verification.

### Automated verification update
- Commit `10bd00d0f16dec8307e9b32d3c54a962b8af1ca7` passed `testDebugUnitTest`, plugin build and index generation in [GitHub Actions run 38047192466](https://github.com/berr49300/cloudstream-film-repo/actions/runs/38047192466).
- The public publishing step was skipped as intended; a test artifact was uploaded to the workflow run.
- This verifies compilation and the parser's unit fixtures only. It does not prove that Dizilla's live subtitle URLs are valid or that CloudStream displays them, and it does not validate live category completeness.


## Process improvement: discover the source of truth before asking for sample links (2026-10-10)

### Lesson from the first Dizilla integration
The first investigation requested a homepage-to-episode URL too early. Later evidence showed that important page data is loaded from an encrypted Next.js state/API flow. In future integrations, a visible page URL is only a navigation example—not proof that HTML contains the data or that no API is involved.

### New required default
- Follow `docs/SITE_DISCOVERY_PLAYBOOK.md` before implementation and fill `docs/SITE_PROFILE_TEMPLATE.md`.
- Inspect public HTML, redirects/status/content type, hydration state, scripts/bundles, API routes, iframes and response shapes before requesting user-supplied URLs.
- Ask the user for an artifact only after naming the exact unobservable gap it fills (for example, device-only playback behavior or region/session-specific response).
- Record the full flow from catalog/search to detail, episode, player, media and subtitles; do not assume all paths share one architecture.
- Reuse generic parsing utilities only for stable shared formats. Keep per-site APIs and player-family protocols isolated, with sanitized fixtures and regression tests.
- Distinguish CONFIRMED, HYPOTHESIS, BLOCKED and NOT TESTED. Build success does not promote runtime hypotheses to facts.
- A universal auto-scraper is not a realistic acceptance criterion. The goal is a universal *investigation and adapter framework*: fast architecture discovery, reusable generic components, isolated site/player adapters and reliable tests.


### Subtitle transport headers hypothesis (2026-10-10)
- The CloudStream core API defines SubtitleFile.headers and newSubtitleFile accepts an initializer. The ContentX extractor previously emitted only language + URL.
- Commit 245b47a7585be9b237c8110421803ca622f3f8f9 now sets the subtitle request Referer to the ContentX iframe URL. This is a targeted, testable hypothesis because subtitle hosts may validate their embedding page.
- Status: build pending at time of note; runtime not verified. If still failing, inspect actual subtitle response status/content-type/body and supported headers before changing parser regex again.


## FullHDFilmizlesene: CloudStream vs Stremio boundary (2026-10-10)

- A public Stremio reference project documents the site's autocomplete and `scx` source encoding. This is useful prior-art for discovering the site's protocol, but it does not validate CloudStream behavior.
- Stremio implementations may resolve streams on a server, proxy HLS playlists, rewrite segment URLs and attach headers. CloudStream providers instead need to return data through the app's `MainAPI` and supported `ExtractorApi`/extractor registry; server proxy behavior cannot be assumed to exist in the app.
- Current external inspection of the site returned 403, so homepage DOM selectors and live response shapes are BLOCKED, not confirmed. Search snippets and a reference README are not substitutes for sanitized live fixtures.
- The current FullHDFilmizlesene module compiled and packaged in GitHub Actions, but the user reports the provider homepage only displays “None” and “Random”. Treat catalog as a runtime failure until a real response and selector-level regression test establish the cause.
- The initial install error's root cause is UNKNOWN. The user screenshots show the plugin later appeared installed, but do not include a diagnostic message/log that identifies the cause.
- Required change to process: for every new site, separate (1) site protocol evidence, (2) CloudStream API/packaging correctness, and (3) real-device catalog/playback/subtitle verification. Never use a successful build as a proxy for the other two.

## DDizi runtime lesson (2026-10-11)

- The public `/diziler/{id}/{slug}` page mixes the current series' “Dizinin Diğer Bölümleri” with a separate “Bugünkü Bölümler” list. A provider that globally selects every `/izle/` link will silently attach other shows as episodes. Filter by the verified series identity and follow only pagination routes under that exact series path.
- Current DDizi episode pages visibly link to the correct series and use a same-site `/player/oynat/{token}` wrapper. A provider must not blanket-reject every same-origin URL when the site itself serves its player wrapper on that origin; whitelist only the observed player route, then inspect the next iframe/player hop.
- One historical page exposed `ddizi.re/player/youtube.php?id=...`; that is evidence for that player family only, not proof that all current DDizi episodes use YouTube. Do not apply FilmMakinesi's CloseLoad/Rapid JavaScript decoder unless the actual player response confirms the same family or encoding.
- The web inspection environment received 404 on the observed player wrapper link. Treat this as an inspection-context limitation until a real request with the episode referer is captured; do not label it a confirmed site-wide outage.
- Build success is not runtime success. DDizi's code is now marked experimental while series matching, pagination and the bounded player wrapper resolver are being tested. Record current link emission, playable media type, request referer, video/audio output and subtitles separately.



## SelcukFlix malformed detail URLs and encrypted app payload (2026-10-25)

- User-visible symptom: CloudStream displayed `https://selcukflix.appdizi/carrie` and failed with `UnknownHostException`, while the website homepage itself rendered. The detail URL passed to CloudStream was malformed; that failure occurs before any player extraction.
- Root-cause corrections: a site-owned URL normalizer now repairs the missing slash before known `/dizi/` and `/film/` paths, canonicalizes legacy SelcukFlix domains to the configured `.app` origin, and has regression tests for absolute, relative and malformed URLs. The page/API payload decryptor now uses the current public client-side AES key documented by maintained SelcukFlix `.app` provider implementations rather than the previous unverified derived-key hypothesis.
- Link handling now reports failure when no source candidate was actually emitted instead of treating non-empty page text as playback success. Plugin version was incremented to 2.
- Verification status: deterministic regression fixtures were added. Live `.app` response inspection remains BLOCKED in this environment (the public web fetch failed), and actual playback/subtitles on the user's CloudStream device remain NOT TESTED. No CAPTCHA/Cloudflare bypass was added.

- Follow-up source-schema fix: current `RelatedResults.getSerieSeasonAndEpisodes.result` objects use snake_case fields (`season_no`, `episode_no`, `episode_text`, `used_slug`). The parser now accepts those observed field names and includes a sorted, sanitized multi-season fixture. This prevents series detail pages from appearing empty when hydration data is available.

- Endpoint/routes alignment: search now uses the observed lowercase `/api/bg/searchcontent` path and the site's root referer; pagination uses `page=` and the main-page list is limited to the verified `/film-izle` and `/dizi-izle` routes instead of assuming `/trend` and `/kesfet` are standalone routes.


## SelcukFlix catalog and source resolution correction (2026-10-25)

- User-provided CloudStream screenshots show the detail page loads metadata and eight episode rows, but every row reports “Bağlantı bulunamadı”; the homepage also exposes richer IMDb, domestic-film, recent-episode, dubbed/subtitled, anime and Asia filters than the first provider version.
- Catalog: restored POST parameter support for `/api/bg/findMovies` and `/api/bg/findSeries`, current-page pagination, known category IDs, IMDb minimum score and Turkish-country filters; added separate “Güncel Bölümler” views and handles episode-page hydration by linking to its owning series when the response provides that relationship.
- Sources: current source-field schema can carry `source_content` iframe markup in the decrypted `content.result.RelatedResults` envelope. Added a parser that extracts only observed iframe URLs from those fields; `/iframe.php?v=...` frames are resolved by a dedicated SelcukFlix ContentX handler rather than assuming the generic extractor registry will match their changing hostnames.
- Player: follows the normal `iframe.php?v=TOKEN` -> `source2.php?v=TOKEN` request path, emits every media file returned by the ordinary source response, discovers declared subtitle tracks, and follows both the primary player token and an explicitly labeled Turkish-dub token when present. Wrapped site payloads are tried using both known client-side payload encodings. No URL, key, source ID or media host is synthesized when not present in a response; CAPTCHA/Cloudflare challenges are not bypassed.
- Regression coverage includes malformed URLs, nested current page payloads, episode field mapping, observed iframe extraction, source API media declarations, player token/dub-token parsing, subtitle variants and access-challenge rejection.
- Verification status: CI unit/build/packaging must pass before publishing; live `.app` endpoints and actual audio/subtitle/video output on a real CloudStream device remain NOT TESTED because the inspection environment cannot fetch the live domain. The user should retest v3 and provide a fresh diagnostic if any particular source host remains unavailable.


## SelcukFlix v4 source probe and poster recovery (2026-10-25)

- Resolver path: scans decoded page payload, Next.js state, player-related script blocks and raw HTML independently; resolver IDs are preserved even when candidates resolve to the same underlying media URL.
- ContentX/Pichive: maintains caller-supplied referer variants and emits separately numbered raw-file and derived `m.php` → `master.m3u8` candidate names. The derived URL is still a hypothesis until manually played.
- When a discovered player URL does not resolve through installed extractors, the provider emits a `PROBE ONLY (player URL, media NOT verified)` candidate under its original discovery ID. This is a diagnostic/manual-test entry, not a playback-success claim.
- Posters: use explicit poster/face fields first, recursively inspect nested API objects, parse lazy-load/srcset and OG/Twitter image metadata, reject known placeholders, and attempt a bounded detail-page fallback for at most 8 missing posters on the unfiltered first-page series catalog.
- Added fixture tests for nested/placeholder poster discovery and raw/derived media candidate IDs.
- V4 CI result: PASS for code commit `9837f4d08c9a51b4f32de2339d39d28350a13c4a` (unit tests, `.cs3` build, and repository validator): https://github.com/berr49300/cloudstream-film-repo/actions/runs/40195467117. The publish step was skipped for the open PR. Real site data and user-device video/subtitle/poster rendering remain NOT TESTED because the live site cannot be fetched from this environment; no access-control or DRM bypass is attempted.


## 2026-10-26 — SelcukFlix multi-source fallback follow-up

- Further defect: the player parser treated one candidate from the unverified `m.php` → `master.m3u8` transformation as sufficient and skipped the ordinary extractor registry. A candidate being emitted did not establish that it would play.
- Follow-up: emit both the raw `m.php` endpoint and the derived playlist hypothesis; try CloudStream's registered extractor path for each iframe regardless of whether the site-specific parser emitted a candidate; keep duplicate-link filtering.
- Added/updated sanitized parser tests for raw + derived candidates while preserving escaped query parameters.
- Live device playback remains NOT TESTED; the derived playlist path remains HYPOTHESIS until tested.

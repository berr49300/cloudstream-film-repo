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

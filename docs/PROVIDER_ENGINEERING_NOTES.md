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
- Opening a video/source can remain on a spinner or take a long time.
- Subtitle menu can show options such as Turkish, but selecting them does not produce visible subtitles.
- Directly opening an episode can behave differently from advancing to it with the next-episode control.
- Some home/category lists previously mixed anime, Korean dramas, and regular series.

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
- Current source module version observed: `3`; published `builds/plugins.json` also advertises version `3`.
- Repository is currently public. Making the whole repository private would also make the published `builds` branch and raw plugin artifacts inaccessible to unauthenticated CloudStream clients. If source privacy is required while distributing a working plugin, use a private source repository and a separate public distribution repository/branch with only the release artifacts and required index.
- Changing a public repository to private does not make existing public forks private. Review GitHub's visibility-change warnings before proceeding.

### Status
- Performance root cause: not confirmed.
- Subtitle root cause: not confirmed.
- Direct-vs-next episode discrepancy: not confirmed.
- Category selector/type fix: compiles, but still needs in-app validation.

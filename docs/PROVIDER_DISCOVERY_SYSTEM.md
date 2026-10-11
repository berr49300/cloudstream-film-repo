# Provider discovery system

## Goal

Before implementing a new CloudStream provider, collect evidence about how the page exposes playback and choose the smallest compatible resolver. Do not assume every site needs JavaScript execution, and do not treat “a URL was extracted” as proof that playback works.

## First-pass probe

Run the developer-only, read-only probe:

\`\`\`sh
python tools/provider_probe.py --url "https://example.invalid/movie-page"
# or inspect a saved response, e.g. captured during an authorized browser session:
python tools/provider_probe.py --html-file page.html --base-url "https://example.invalid/movie-page"
# machine-readable report:
python tools/provider_probe.py --url "https://example.invalid/movie-page" --json
\`\`\`

It reports observable evidence: media-looking URLs, iframe hosts, player configuration, common packed-JavaScript signatures, API/XHR clues, obfuscation clues, and DRM-related tokens. The classifications are hints, not proof. It uses only an ordinary HTTP request and does not bypass bot checks or access controls. If the site rejects the request, save the page/player response from a normal authorized browser session and analyze that file instead.

## Decision tree

1. **Direct media (.m3u8, .mp4, .mpd) found:** check response status/content type, required Referer/cookies, and whether the HLS/DASH manifest is actually readable. Keep the source's identity and request headers.
2. **Iframe found:** follow the iframe chain in order. If its hostname is supported by CloudStream's loadExtractor, use that first. Write a custom extractor only for an unsupported host or confirmed site-specific behavior.
3. **Player config found (sources, file, data-video_url, JSON-LD):** parse the known configuration before considering JavaScript evaluation.
4. **Packed or obfuscated JavaScript found:** save a minimal real response as a regression fixture. Identify the exact transform, add a bounded decoder, and preserve a fallback only when it covers a demonstrated older format. Never execute arbitrary page scripts without limits.
5. **API/XHR clues found:** determine the actual request, parameters, token/cookie dependencies, and response schema from an authorized browser session. Do not infer an endpoint just because a string looks like /api/.
6. **No signal found:** compare the initial HTML with the DOM/network response after selecting the player. The content might be injected after interaction, hidden behind a normal session, or unavailable to a static request.
7. **DRM clues found:** determine support and authorization before building an extractor. A media URL alone is not enough to make DRM playback work.

## Keep diagnosis and production separate

The probe belongs in tools/, not in each shipped plugin. This keeps installed .cs3 files small and avoids shipping debugging heuristics to users. The production provider should retain only the site-specific selectors, the selected extractor/resolver path, and the smallest proven decoder.

## Required evidence before calling a provider done

Record each stage independently:

- **Page parsed:** expected film/episode page was retrieved.
- **Player candidates found:** all observed video buttons/iframes are accounted for.
- **Source decoded:** at least one candidate is a plausible media URL.
- **Request validated:** URL responds when requested with the required headers and session state.
- **Manifest validated:** HLS/DASH playlist or direct file response has a plausible media content type.
- **Playback verified:** the stream actually starts in CloudStream on a real device.

A green compile only proves the code builds. Unit tests using saved HTML prove that the parser handles those fixtures. Neither alone proves the current live site plays.

## FilmMakinesi example

The current FilmMakinesi provider collects data-video_url entries and player iframe URLs. It dispatches CloseLoad/Rapid-family hosts to the focused FilmMakinesiPlayerResolver and leaves other hosts to CloudStream's generic loadExtractor. The resolver checks explicit player configuration and legacy payload forms, with bounded JavaScript evaluation as a targeted path. That is the pattern to reuse: **identify evidence first, then select one resolver path**, rather than applying every technique to every site.

Useful references:
- [NeoWatch FilmMakinesi provider](https://github.com/neoser1984/neowatch/blob/main/FilmMakinesi/src/main/kotlin/com/neowatch/FilmMakinesi.kt)
- [FilmMakinesi resolver in this repository](https://github.com/berr49300/cloudstream-film-repo/blob/main/FilmMakinesi/src/main/kotlin/com/kra/cloudstream/filmmakinesi/FilmMakinesiPlayerResolver.kt)
- [FilmMakinesi evidence profile](SITE_PROFILE_FILMMAKINESI.md)
